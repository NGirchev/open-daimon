# DOC/XLS Document: Tika RAG Flow

> **Manual tests:**
> - `DocRagOllamaManualIT`, `DocRagOpenRouterManualIT` — DOC files
> - `XlsRagOllamaManualIT`, `XlsRagOpenRouterManualIT` — XLS files
>
> Run with: `./mvnw -pl opendaimon-app -am clean test-compile failsafe:integration-test failsafe:verify -Dit.test=<TestClass> -Dfailsafe.failIfNoSpecifiedTests=false -Dmanual.ollama.e2e=true`

When a user uploads a DOC, XLS, DOCX, XLSX or other office document, the system extracts
text via Apache Tika (through Spring AI's `TikaDocumentReader`), indexes chunks in
VectorStore, and builds an augmented prompt for the LLM. No vision model is needed.

## Supported Document Types

| Extension | MIME Type | Document Type |
|-----------|-----------|---------------|
| `.doc` | `application/msword` | `doc` |
| `.docx` | `application/vnd.openxmlformats-officedocument.wordprocessingml.document` | `docx` |
| `.xls` | `application/vnd.ms-excel` | `xls` |
| `.xlsx` | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` | `xlsx` |
| `.ppt` | `application/vnd.ms-powerpoint` | `ppt` |
| `.pptx` | `application/vnd.openxmlformats-officedocument.presentationml.presentation` | `pptx` |
| `.txt`, `.csv`, `.html`, `.md`, `.json`, `.xml`, `.rtf`, `.odt`, `.ods`, `.odp`, `.epub` | various | mapped by extension |

Detection is done in `SpringDocumentContentAnalyzer.extractDocumentType()` via `DocumentTypeMapping` —
checks MIME type patterns first, then file extension fallback. This logic was extracted from
`SpringAIGateway` as part of the architecture refactoring.

## First Message (Document Upload + Question)

```mermaid
sequenceDiagram
    actor User
    participant TG as TelegramFileService
    participant MS as MessageService
    participant PL as AIRequestPipeline
    participant OR as SpringDocumentOrchestrator
    participant AN as SpringDocumentContentAnalyzer
    participant PR as SpringDocumentPreprocessor
    participant DPS as DocumentProcessingService
    participant RAG as FileRAGService
    participant VS as VectorStore
    participant CF as DefaultAICommandFactory
    participant GW as SpringAIGateway
    participant Mem as ChatMemory
    participant LLM as Chat Model

    User->>TG: Send DOC/XLS + question
    TG->>TG: Download file from Telegram API
    TG->>TG: Detect MIME type, map to extension
    TG->>TG: Save to MinIO (storageKey)
    TG->>MS: Attachment(storageKey, mimeType, filename, data)

    MS->>MS: saveUserMessage(text, attachments)
    Note over MS: Persists to DB with sequenceNumber

    MS->>PL: IChatCommand(userQuery, attachments, metadata={})

    PL->>OR: orchestrate(command)

    OR->>AN: analyze(attachment)
    AN->>AN: extractDocumentType(mimeType, filename)
    Note over AN: "application/msword" → "doc"<br/>"application/vnd.ms-excel" → "xls"<br/>Non-PDF → TEXT_EXTRACTABLE (Tika handles it)
    AN-->>OR: DocumentAnalysisResult(TEXT_EXTRACTABLE, requires CHAT)

    Note over OR,DPS: Phase 1: Extract & Index (Tika)

    OR->>PR: preprocess(attachment, userQuery, TEXT_EXTRACTABLE)
    PR->>DPS: processWithTika(data, filename, documentType)
    DPS->>DPS: TikaDocumentReader — auto-detects format
    DPS->>DPS: Extract text → List<Document>
    DPS->>DPS: TokenTextSplitter(chunkSize=800, overlap=100)
    DPS->>DPS: Add metadata: documentId, originalName, type
    DPS->>VS: add(chunks) — embeddings generated automatically
    DPS-->>PR: documentId
    PR-->>OR: DocumentPreprocessingResult(documentId, relevantChunks)

    Note over OR,VS: Phase 2: Semantic Search

    OR->>RAG: findRelevantContext(userQuery, documentId)
    RAG->>VS: similaritySearch(query, topK=5, threshold)
    VS-->>RAG: Top-K relevant chunks
    RAG-->>OR: relevantChunks

    Note over OR: Phase 3: Build Augmented Prompt & Store Metadata

    OR->>OR: storeDocumentIdsInCommandMetadata(documentIds, command)
    Note over OR: metadata.put(RAG_DOCUMENT_IDS_FIELD, documentId)

    OR->>OR: buildRagAugmentedQuery(relevantChunks, userQuery)
    OR-->>PL: OrchestratedChatCommand(augmentedQuery, attachments, metadata)

    PL->>CF: createCommand(orchestratedCommand)
    Note over CF: No IMAGE attachments, TEXT_EXTRACTABLE → no VISION added
    CF-->>PL: ChatAICommand(CHAT capability)

    PL->>GW: ChatAICommand(augmentedQuery, metadata)

    GW->>GW: addSystemAndUserMessagesIfNeeded()
    GW->>Mem: add(conversationId, messages)
    GW->>LLM: [SystemMessage(role), UserMessage(augmentedPrompt + placeholder)]
    LLM-->>GW: Response with document context
    GW->>MS: saveAssistantMessage(response)
    Note over MS: Handler reads metadata, persists ragDocumentIds to USER message
    GW-->>User: Answer based on document content
```

## Follow-Up Message (No Attachments)

```mermaid
sequenceDiagram
    actor User
    participant Handler as MessageHandler
    participant MS as MessageService
    participant PL as AIRequestPipeline
    participant OR as SpringDocumentOrchestrator
    participant VS as VectorStore
    participant CF as DefaultAICommandFactory
    participant GW as SpringAIGateway
    participant Mem as ChatMemory
    participant LLM as Chat Model

    User->>Handler: Follow-up question (no attachments)

    Handler->>MS: findRagDocumentIds(thread)
    MS-->>Handler: List<documentId> from USER message metadata

    Handler->>Handler: metadata.put(RAG_DOCUMENT_IDS_FIELD, documentIds)
    Handler->>PL: IChatCommand(text, [], metadata={ragDocumentIds=...})

    PL->>OR: processFollowUpRagIfAvailable(command)
    Note over OR: No attachments — read documentIds from command.metadata()

    OR->>OR: command.metadata().get(RAG_DOCUMENT_IDS_FIELD)
    OR->>VS: findAllByDocumentId(documentId) — per document
    VS-->>OR: allChunks

    OR->>OR: Build RAG context prefix from chunks
    OR-->>PL: OrchestratedChatCommand(RAG prefix + followUp)

    PL->>CF: createCommand(orchestratedCommand)
    CF-->>PL: ChatAICommand(CHAT capability)

    PL->>GW: ChatAICommand(RAG prefix + followUp)

    GW->>Mem: get(conversationId)
    Note over Mem: Loads history from DB via MessageWindowChatMemory

    GW->>LLM: [System, History, UserMessage(RAG prefix + followUp)]
    Note over LLM: Model sees fresh RAG context<br/>from VectorStore + chat history

    LLM-->>GW: Follow-up response
    GW-->>User: Answer with dynamically retrieved RAG context
```

## Key Design Points

1. **Tika handles all non-PDF office formats** — `TikaDocumentReader` from Spring AI
   auto-detects the file format and extracts text. No format-specific code needed.

2. **Same RAG pipeline as text PDF** — after text extraction, the flow is identical to
   [`text-pdf-rag.md`](./text-pdf-rag.md): chunk, index, search, augment prompt.

3. **Type detection in `SpringDocumentContentAnalyzer`** — MIME type and extension mapping
   (previously `SpringAIGateway.extractDocumentType()`) now lives in
   `SpringDocumentContentAnalyzer`. Non-PDF documents always return `TEXT_EXTRACTABLE`;
   only PDFs require the `PdfTextDetector` check.

4. **DocumentIds stored in USER message metadata** — the orchestrator writes documentIds into
   `AICommand.metadata` under `RAG_DOCUMENT_IDS_FIELD`. The handler persists them on the
   USER message via `OpenDaimonMessageService.updateRagMetadata()`.

5. **AttachmentType.PDF used as catch-all** — the `AttachmentType` enum currently only has
   `IMAGE` and `PDF`. All non-image documents (DOC, XLS, etc.) are classified as
   `AttachmentType.PDF` at the Telegram layer. Real format detection happens in
   `SpringDocumentContentAnalyzer` via MIME type and extension mapping.

6. **No vision model needed** — unlike image-only PDFs (see
   [`image-pdf-vision-cache.md`](./image-pdf-vision-cache.md)), office documents contain
   extractable text. Tika handles the binary format decoding.

7. **XLS/XLSX specifics** — Tika extracts cell values as text, preserving tabular structure
   to a degree. Column headers and data rows become searchable text chunks.
