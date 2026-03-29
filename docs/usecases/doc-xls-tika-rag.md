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

Detection is done in `SpringAIGateway.extractDocumentType()` via `DocumentTypeMapping` —
checks MIME type patterns first, then file extension fallback.

## First Message (Document Upload + Question)

```mermaid
sequenceDiagram
    actor User
    participant TG as TelegramFileService
    participant MS as MessageService
    participant GW as SpringAIGateway
    participant DPS as DocumentProcessingService
    participant RAG as FileRAGService
    participant VS as VectorStore
    participant Mem as ChatMemory
    participant LLM as Chat Model

    User->>TG: Send DOC/XLS + question
    TG->>TG: Download file from Telegram API
    TG->>TG: Detect MIME type, map to extension
    TG->>TG: Save to MinIO (storageKey)
    TG->>MS: Attachment(storageKey, mimeType, filename, data)

    MS->>MS: saveUserMessage(text, attachments)
    Note over MS: Persists to DB with sequenceNumber

    MS->>GW: ChatAICommand(userQuery, attachments, metadata={})

    GW->>GW: addSystemAndUserMessagesIfNeeded()
    GW->>GW: processRagIfEnabled(userQuery, attachments)
    GW->>GW: extractDocumentType(mimeType, filename)
    Note over GW: "application/msword" → "doc"<br/>"application/vnd.ms-excel" → "xls"

    Note over GW,DPS: Phase 1: Extract & Index (Tika)

    GW->>DPS: processWithTika(data, filename, documentType)
    DPS->>DPS: TikaDocumentReader — auto-detects format
    DPS->>DPS: Extract text → List<Document>
    DPS->>DPS: TokenTextSplitter(chunkSize=800, overlap=100)
    DPS->>DPS: Add metadata: documentId, originalName, type
    DPS->>VS: add(chunks) — embeddings generated automatically
    DPS-->>GW: documentId

    Note over GW,VS: Phase 2: Semantic Search

    GW->>RAG: findRelevantContext(userQuery, documentId)
    RAG->>VS: similaritySearch(query, topK=5, threshold)
    VS-->>RAG: Top-K relevant chunks
    RAG-->>GW: relevantChunks

    Note over GW: Phase 3: Build Augmented Prompt & Store Metadata

    GW->>GW: storeDocumentIdsInCommandMetadata(documentIds, command)
    Note over GW: metadata.put(RAG_DOCUMENT_IDS_FIELD, documentId)

    GW->>GW: buildRagAugmentedQuery(relevantChunks, userQuery)
    GW->>GW: buildRagPlaceholder(documentAttachments)

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
    participant GW as SpringAIGateway
    participant VS as VectorStore
    participant Mem as ChatMemory
    participant LLM as Chat Model

    User->>Handler: Follow-up question (no attachments)

    Handler->>MS: findRagDocumentIds(thread)
    MS-->>Handler: List<documentId> from USER message metadata

    Handler->>Handler: metadata.put(RAG_DOCUMENT_IDS_FIELD, documentIds)
    Handler->>GW: ChatAICommand(text, [], metadata={ragDocumentIds=...})

    GW->>GW: processFollowUpRagIfAvailable()
    Note over GW: No attachments — read documentIds from command.metadata()

    GW->>GW: command.metadata().get(RAG_DOCUMENT_IDS_FIELD)
    GW->>VS: findAllByDocumentId(documentId) — per document
    VS-->>GW: allChunks

    GW->>GW: Build RAG context prefix from chunks

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

2. **Same RAG pipeline as PDF** — after text extraction, the flow is identical to
   [`text-pdf-rag.md`](./text-pdf-rag.md): chunk, index, search, augment prompt.

3. **DocumentIds stored in USER message metadata** — the gateway writes documentIds into
   `AICommand.metadata` under `RAG_DOCUMENT_IDS_FIELD`. The handler persists them on the
   USER message via `OpenDaimonMessageService.updateRagMetadata()`.

4. **AttachmentType.PDF used as catch-all** — the `AttachmentType` enum currently only has
   `IMAGE` and `PDF`. All non-image documents (DOC, XLS, etc.) are classified as
   `AttachmentType.PDF` at the Telegram layer. Real format detection happens in the gateway
   via MIME type and extension mapping.

5. **No vision model needed** — unlike image-only PDFs (see
   [`image-pdf-vision-cache.md`](./image-pdf-vision-cache.md)), office documents contain
   extractable text. Tika handles the binary format decoding.

6. **XLS/XLSX specifics** — Tika extracts cell values as text, preserving tabular structure
   to a degree. Column headers and data rows become searchable text chunks.
