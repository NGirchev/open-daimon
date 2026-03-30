# RAG (Retrieval-Augmented Generation) Logic

## Architecture Overview

RAG in open-daimon is **prompt-based** (not function calling). Documents are split into chunks, indexed in VectorStore, and relevant chunks are inserted into an augmented prompt at query time.

### Components

| Component | Module | Role |
|-----------|--------|------|
| `AIRequestPipeline` | `opendaimon-common` | Orchestrates document preprocessing before factory; handlers call `pipeline.prepareCommand()` |
| `IDocumentOrchestrator` / `SpringDocumentOrchestrator` | `opendaimon-common` / `opendaimon-spring-ai` | Coordinates document preprocessing + RAG query building + follow-up RAG |
| `IDocumentPreprocessor` / `SpringDocumentPreprocessor` | `opendaimon-common` / `opendaimon-spring-ai` | ETL: text extraction (PDFBox/Tika), vision OCR for image-only PDFs, RAG indexing |
| `IDocumentContentAnalyzer` / `SpringDocumentContentAnalyzer` | `opendaimon-common` / `opendaimon-spring-ai` | Determines if a document needs VISION (delegates to `PdfTextDetector`) |
| `PdfTextDetector` | `opendaimon-spring-ai` | Lightweight PDFBox check — can text be extracted? No VectorStore writes |
| `DocumentProcessingService` | `opendaimon-spring-ai` | ETL pipeline: Extract → Transform → Load into VectorStore |
| `FileRAGService` | `opendaimon-spring-ai` | VectorStore search + augmented prompt construction |
| `DefaultAICommandFactory` | `opendaimon-common` | Creates `ChatAICommand`; adds VISION capability when `IDocumentContentAnalyzer` detects image-only PDF |
| `SpringAIGateway` | `opendaimon-spring-ai` | Thin executor: model selection, message building, chat call — no document processing |
| `SummarizingChatMemory` | `opendaimon-spring-ai` | Loads message history from DB into Spring AI Messages |
| `RAGProperties` | `opendaimon-spring-ai` | Configuration: chunk-size, top-k, similarity-threshold, prompts |
| `SimpleVectorStore` | Spring AI (in-memory) | Embedding storage (lost on restart) |

### Configuration (application.yml)

```yaml
open-daimon.ai.spring-ai.rag:
  enabled: true
  chunk-size: 800        # tokens per chunk
  chunk-overlap: 100     # overlap between chunks
  top-k: 5               # number of chunks to return
  similarity-threshold: 0.7
  prompts:
    augmented-prompt-template: |
      Based on the following context from the document, answer the user's question.
      If the context doesn't contain relevant information to answer the question,
      say that you couldn't find the answer in the provided documents.

      Context:
      %s

      Question: %s
```

---

## Document Processing Flow (first message with attachment)

Document orchestration happens **before** the factory and gateway, inside `AIRequestPipeline`. The gateway only receives an already-preprocessed `OrchestratedChatCommand` and executes the chat call.

```
Telegram: user sends PDF without caption text
    │
    ▼
TelegramBot.mapToTelegramDocumentCommand()
    │  empty caption → default localized prompt is used for `userText`
    │  example prompt sent to model:
    │  "Analyze this document and provide a brief summary."
    ▼
TelegramFileService.processDocument() → Attachment (key, mimeType, data)
    │
    ▼
TelegramMessageService.saveUserMessage()
    │  Saves to OpenDaimonMessage with attachments JSONB:
    │  [{"storageKey": "document/uuid.pdf", "mimeType": "application/pdf",
    │    "filename": "report.pdf", "expiresAt": "..."}]
    ▼
AIRequestPipeline.prepareCommand(IChatCommand)
    │
    ├── SpringDocumentOrchestrator.orchestrate(command)
    │       │
    │       ├── document attachments not empty → process document
    │       │       │
    │       │       ▼
    │       │   IDocumentContentAnalyzer.analyze(attachment)
    │       │       → PdfTextDetector: check if PDF has text layer
    │       │       → returns TEXT_EXTRACTABLE or IMAGE_ONLY
    │       │
    │       │   SpringDocumentPreprocessor.preprocess()
    │       │       │
    │       │       ├── TEXT_EXTRACTABLE → DocumentProcessingService.processPdf()
    │       │       │       │  1. PagePdfDocumentReader (PDFBox) → pages
    │       │       │       │  2. TokenTextSplitter → chunks (800 tokens, 100 overlap)
    │       │       │       │  3. Metadata: documentId (UUID), originalName, type
    │       │       │       │  4. VectorStore.add(chunks)
    │       │       │       ▼
    │       │       │   FileRAGService.findRelevantContext(query, documentId)
    │       │       │       → top-5 chunks with similarity > 0.7
    │       │       │
    │       │       └── IMAGE_ONLY → renderPdfToImageAttachments() → JPEG images
    │       │               │  extractTextFromImagesViaVision() (OCR)
    │       │               │  DocumentProcessingService.processExtractedText()
    │       │               ▼
    │       │           VectorStore.add(chunks with type="pdf-vision")
    │       │
    │       ├── storeDocumentIdsInCommandMetadata()
    │       │       metadata.put(RAG_DOCUMENT_IDS_FIELD, documentId)
    │       │
    │       └── buildRagAugmentedQuery(relevantChunks, userQuery)
    │               → "Context:\n...\n\nQuestion: ..."
    │
    ▼
DefaultAICommandFactory.createCommand()
    │  IDocumentContentAnalyzer already ran in orchestrator
    │  Factory adds VISION capability if IMAGE attachments present
    │  (for image-only PDFs: after OCR, images may be removed; for failed OCR, images remain)
    ▼
OrchestratedChatCommand(augmentedUserText, preprocessedAttachments)
    │
    ▼
SpringAIGateway.generateResponse()
    │  Model selection (capabilities + priority)
    │  Message building (system + user + media)
    ▼
SpringAIChatService.streamChat() → model response
```

---

## Follow-up Query Flow (no attachment)

This is a key capability. Follow-up queries without attachments continue to benefit from previously indexed RAG documents.

### Problem (before changes)

1. User sends PDF → RAG processes it, model responds
2. User asks "what's in the file?" → RAG was skipped (`documentAttachments.isEmpty()` → return)
3. Model had no knowledge of the file because:
   - History was loaded as plain text (no attachment info)
   - VectorStore was not queried
   - Attachment context SystemMessage may be lost during summarization

### Solution (current architecture)

Two mechanisms work together:

#### 1. History enrichment with attachment metadata (`SummarizingChatMemory`)

When loading messages from DB during summarization, `convertToSpringMessage()` calls `enrichWithAttachmentInfo()`:

```
Before: USER → "Analyze this document and provide a brief summary."
After:  USER → "Analyze this document and provide a brief summary.\n[Attached files: \"report.pdf\" (application/pdf)]"
```

The model now sees in conversation history which files the user previously uploaded.

#### 2. Follow-up RAG via `SpringDocumentOrchestrator`

```
AIRequestPipeline.prepareCommand(followUpCommand)
    │  no document attachments
    ▼
SpringDocumentOrchestrator.processFollowUpRagIfAvailable(command)
    │
    ├── command.metadata() has RAG_DOCUMENT_IDS_FIELD → use stored documentIds
    │       → VectorStore.findAllByDocumentId(documentId)  ← threshold=0.0
    │       → returns all chunks for that document
    │
    ├── no documentIds in metadata → skip follow-up RAG
    │
    └── chunks found → buildRagAugmentedQuery(userQuery, chunks)
            → augmented prompt with context from previously uploaded documents
```

The handler reads `ragDocumentIds` from the USER message metadata in the thread and injects them into `AICommand.metadata` before calling the pipeline. This ensures the orchestrator can retrieve the correct document chunks even after a restart (as long as VectorStore is not cleared).

---

## Defensive Fallback for Empty Queries

If userQuery is empty/blank but a document is attached, `SpringDocumentOrchestrator` substitutes a default query:

```java
// SpringDocumentOrchestrator.orchestrate()
if (userQuery == null || userQuery.isBlank()) {
    userQuery = "Summarize this document and provide key points.";
}
```

In practice, with the default localized prompt fix, this case is unlikely — this is defence-in-depth.

---

## Localized Default Prompt for Documents

### Problem

Photos had a localized fallback prompt (`telegram.photo.default.prompt`), but documents used an empty string.

### Solution

`TelegramBot.mapToTelegramDocumentCommand()` now uses the same logic as `mapToTelegramPhotoCommand()`:

```java
String caption = message.getCaption();
String userText = caption != null && !caption.isBlank()
        ? caption
        : messageLocalizationService != null
                ? messageLocalizationService.getMessage("telegram.document.default.prompt", telegramUser.getLanguageCode())
                : "Analyze this document and provide a brief summary.";
```

`telegram.document.default.prompt` and `telegram.photo.default.prompt` are locale keys.
The final value is selected by `telegramUser.languageCode` through `MessageLocalizationService`.
The English literal in code is an emergency fallback only (when localization service is not wired).

---

## Data Storage

### OpenDaimonMessage.attachments (JSONB)

```json
[
  {
    "storageKey": "document/5e7a0879-10c5-4279-9106-ac805461e620.pdf",
    "mimeType": "application/pdf",
    "filename": "udemy_multithreading_cert.pdf",
    "expiresAt": "2026-03-27T19:38:27.130134794Z"
  }
]
```

### OpenDaimonMessage.metadata (JSONB) — USER message

After the first message with a document, the handler writes `ragDocumentIds` into the USER message metadata via `OpenDaimonMessageService.updateRagMetadata()`:

```json
{
  "ragDocumentIds": ["a3f2c1d4-..."],
  "ragFilenames": ["report.pdf"]
}
```

This is how documentIds survive across sessions and are available for follow-up queries.

### VectorStore Chunk Metadata

```json
{
  "documentId": "a3f2c1d4-...",
  "originalName": "report.pdf",
  "type": "pdf"
}
```

---

## Known Limitations

| Limitation | Cause | Possible Solution |
|------------|-------|-------------------|
| VectorStore data lost on restart | SimpleVectorStore is in-memory | Migrate to PGVector or Elasticsearch |
| Follow-up search requires documentId in metadata | `processFollowUpRagIfAvailable` filters by documentId | If no documentId available, gracefully falls back to chat history only |
| Attachment context SystemMessage may be lost during summarization | SummarizingChatMemory evicts old messages | `enrichWithAttachmentInfo` partially compensates |

---

## Changed Files (Architecture Refactoring)

| File | Change |
|------|--------|
| `AIRequestPipeline.java` (new, common) | Pipeline entry point: wraps orchestrator → factory. Handlers call `pipeline.prepareCommand()` |
| `IDocumentOrchestrator.java` (new, common) | Interface: `orchestrate()` + `processFollowUpRagIfAvailable()` |
| `SpringDocumentOrchestrator.java` (new, spring-ai) | Orchestration logic extracted from `SpringAIGateway`: RAG query building, document ID storage, follow-up RAG |
| `IDocumentPreprocessor.java` (new, common) | Interface: `preprocess(attachment, userQuery, analysisResult)` |
| `SpringDocumentPreprocessor.java` (new, spring-ai) | ETL logic extracted from `SpringAIGateway`: PDF rendering, vision OCR, Tika, RAG indexing |
| `IDocumentContentAnalyzer.java` (new, common) | Interface: `analyze(attachment)` → `DocumentAnalysisResult` |
| `SpringDocumentContentAnalyzer.java` (new, spring-ai) | MIME/extension type detection extracted from `SpringAIGateway.extractDocumentType()` |
| `PdfTextDetector.java` (new, spring-ai) | Lightweight PDFBox text presence check; no VectorStore writes |
| `OrchestratedChatCommand.java` (new, common) | Wrapper command substituting userText and attachments after orchestration |
| `SpringAIGateway.java` (modified) | Reduced from ~1167 to ~500 lines; all document/RAG logic removed |
| `DefaultAICommandFactory.java` (modified) | Adds VISION capability when IMAGE attachments present (from PDF rendering fallback) |
| `telegram_en.properties` | + `telegram.document.default.prompt` |
| `telegram_ru.properties` | + `telegram.document.default.prompt` |
| `TelegramBot.java` | Localized fallback prompt for documents (mirrors photo logic) |
| `TelegramBotTest.java` | +4 tests: EN/RU without caption, with caption, blank caption |
| `SummarizingChatMemory.java` | + `enrichWithAttachmentInfo()` — attachment metadata from JSONB appended to history messages |

## Supported Document Formats

PDF, DOCX, DOC, XLSX, XLS, PPTX, PPT, TXT, RTF, ODT, ODS, ODP, CSV, HTML, MD, JSON, XML, EPUB.

PDF is processed via PDFBox (`PagePdfDocumentReader`), all others via Apache Tika (`TikaDocumentReader`).
