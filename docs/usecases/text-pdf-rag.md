# Text-Based PDF: RAG Flow

> **Fixture test:** `TextPdfRagFixtureIT` — run with `./mvnw clean verify -pl opendaimon-app -am -Pfixture`
>
> **Manual tests:**
> - `TextPdfRagOllamaManualIT`, `TextPdfRagOpenRouterManualIT` — single-page `sample.pdf` with follow-up RAG
> - `ImagesWithTextPdfVisionRagOllamaManualIT`, `ImagesWithTextPdfVisionRagOpenRouterManualIT` — 3-page `images_with_text.pdf` with cross-chunk RAG retrieval
>
> Run with: `./mvnw -pl opendaimon-app -am clean test-compile failsafe:integration-test failsafe:verify -Dit.test=<TestClass> -Dfailsafe.failIfNoSpecifiedTests=false -Dmanual.ollama.e2e=true`

When a user uploads a PDF with a text layer (selectable text), the system extracts text
via PDFBox, indexes chunks in VectorStore, and builds an augmented prompt for the LLM.

## First Message (PDF Upload + Question)

```mermaid
sequenceDiagram
    actor User
    participant TG as TelegramFileService
    participant MS as MessageService
    participant PL as AIRequestPipeline
    participant OR as SpringDocumentOrchestrator
    participant PR as SpringDocumentPreprocessor
    participant DPS as DocumentProcessingService
    participant RAG as FileRAGService
    participant VS as VectorStore
    participant CF as DefaultAICommandFactory
    participant GW as SpringAIGateway
    participant Mem as ChatMemory
    participant LLM as Chat Model

    User->>TG: Send PDF + question
    TG->>TG: Download file from Telegram API
    TG->>TG: Save to MinIO (storageKey)
    TG->>MS: Attachment(storageKey, mimeType, filename, data)

    MS->>MS: saveUserMessage(text, attachments)
    Note over MS: Persists to DB with sequenceNumber

    MS->>PL: IChatCommand(userQuery, attachments, metadata={})

    PL->>OR: orchestrate(command)

    Note over OR,DPS: Phase 1: Analyze & Extract & Index

    OR->>OR: IDocumentContentAnalyzer.analyze(attachment)
    Note over OR: PdfTextDetector: PDF has text layer → TEXT_EXTRACTABLE

    OR->>PR: preprocess(attachment, userQuery, TEXT_EXTRACTABLE)
    PR->>DPS: processPdf(pdfBytes, filename)
    DPS->>DPS: PagePdfDocumentReader (PDFBox)
    DPS->>DPS: Extract text → List<Document> (one per page)
    DPS->>DPS: TokenTextSplitter(chunkSize=800, overlap=100)
    DPS->>DPS: Add metadata: documentId, originalName, type="pdf"
    DPS->>VS: add(chunks) — embeddings generated automatically
    DPS-->>PR: documentId
    PR-->>OR: DocumentPreprocessingResult(documentId, relevantChunks)

    Note over OR,VS: Phase 2: Semantic Search

    OR->>RAG: findRelevantContext(userQuery, documentId)
    RAG->>VS: similaritySearch(query, topK=5, threshold=0.7)
    VS-->>RAG: Top-K relevant chunks
    RAG-->>OR: relevantChunks

    Note over OR: Phase 3: Build Augmented Prompt & Store Metadata

    OR->>OR: storeDocumentIdsInCommandMetadata(documentIds, command)
    Note over OR: metadata.put(RAG_DOCUMENT_IDS_FIELD, documentId)

    OR->>OR: buildRagAugmentedQuery(relevantChunks, userQuery)
    OR-->>PL: OrchestratedChatCommand(augmentedQuery, attachments, metadata)

    PL->>CF: createCommand(orchestratedCommand)
    Note over CF: No IMAGE attachments → no VISION added
    CF-->>PL: ChatAICommand(CHAT capability)

    PL->>GW: ChatAICommand(augmentedQuery, metadata)

    GW->>GW: addSystemAndUserMessagesIfNeeded()
    GW->>GW: createUserMessage(augmentedPrompt + placeholder)
    Note over GW: Plain text UserMessage (no images)

    GW->>Mem: add(conversationId, messages)
    Note over Mem: Augmented prompt saved as user message content

    GW->>LLM: [SystemMessage(role), UserMessage(augmentedPrompt)]
    LLM-->>GW: Response with document context
    GW->>MS: saveAssistantMessage(response)
    Note over MS: Handler reads metadata, persists ragDocumentIds to USER message
    GW-->>User: Answer based on PDF content
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
    Note over OR: No document attachments — read documentIds from command.metadata()

    OR->>OR: command.metadata().get(RAG_DOCUMENT_IDS_FIELD)
    OR->>VS: findAllByDocumentId(documentId) — per document, threshold=0.0
    VS-->>OR: allChunks

    OR->>OR: Build RAG context prefix from chunks
    OR-->>PL: OrchestratedChatCommand(RAG prefix + followUp, [], metadata)

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

1. **DocumentIds stored in USER message metadata** — the orchestrator writes documentIds into
   `AICommand.metadata` under `RAG_DOCUMENT_IDS_FIELD`. The handler persists them on the
   USER message via `OpenDaimonMessageService.updateRagMetadata()`.

2. **VectorStore active on both first and follow-up messages** — on first message, chunks
   are indexed and searched. On follow-up, the handler reads stored documentIds from message
   history and injects them into command metadata; the orchestrator fetches fresh chunks from
   VectorStore dynamically (threshold=0.0 to return all chunks for that document).

3. **Chunking strategy** — `TokenTextSplitter` with 800-token chunks and 100-token overlap
   ensures context continuity across chunk boundaries.

4. **Similarity threshold** (0.7) filters out low-relevance chunks, preventing noise
   in the augmented prompt.

5. **Gateway is not involved in RAG** — all document extraction, indexing, and query
   augmentation happens in `AIRequestPipeline` → `SpringDocumentOrchestrator` → `SpringDocumentPreprocessor`
   before the command reaches `SpringAIGateway`.

6. **Image-only PDFs follow a different path** — for scanned/image PDFs and local Ollama
   model constraints, see
   [`docs/usecases/image-pdf-vision-cache.md`](./image-pdf-vision-cache.md).

7. **Office documents (DOC, XLS, etc.) use Tika** — for non-PDF office formats extracted
   via Apache Tika, see [`docs/usecases/doc-xls-tika-rag.md`](./doc-xls-tika-rag.md).

8. **Direct images (JPEG/PNG) bypass RAG** — images sent without a PDF wrapper go directly
   to the vision model without text extraction or indexing. See
   [`docs/usecases/image-vision-direct.md`](./image-vision-direct.md).
