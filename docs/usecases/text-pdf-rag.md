# Text-Based PDF: RAG Flow

> **Fixture test:** `TextPdfRagFixtureIT` — run with `./mvnw clean verify -pl opendaimon-app -am -Pfixture`

When a user uploads a PDF with a text layer (selectable text), the system extracts text
via PDFBox, indexes chunks in VectorStore, and builds an augmented prompt for the LLM.

## First Message (PDF Upload + Question)

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

    User->>TG: Send PDF + question
    TG->>TG: Download file from Telegram API
    TG->>TG: Save to MinIO (storageKey)
    TG->>MS: Attachment(storageKey, mimeType, filename, data)

    MS->>MS: saveUserMessage(text, attachments)
    Note over MS: Persists to DB with sequenceNumber

    MS->>GW: ChatAICommand(userQuery, attachments)

    GW->>GW: addSystemAndUserMessagesIfNeeded()
    GW->>GW: processRagIfEnabled(userQuery, attachments)

    Note over GW,DPS: Phase 1: Extract & Index

    GW->>DPS: processPdf(pdfBytes, filename)
    DPS->>DPS: PagePdfDocumentReader (PDFBox)
    DPS->>DPS: Extract text → List<Document> (one per page)
    DPS->>DPS: TokenTextSplitter(chunkSize=800, overlap=100)
    DPS->>DPS: Add metadata: documentId, originalName, type="pdf"
    DPS->>VS: add(chunks) — embeddings generated automatically
    DPS-->>GW: documentId

    Note over GW,VS: Phase 2: Semantic Search

    GW->>RAG: findRelevantContext(userQuery, documentId)
    RAG->>VS: similaritySearch(query, topK=5, threshold=0.7)
    VS-->>RAG: Top-K relevant chunks
    RAG-->>GW: relevantChunks

    Note over GW: Phase 3: Build Augmented Prompt

    GW->>RAG: createAugmentedPrompt(userQuery, relevantChunks)
    RAG-->>GW: "Context:\n{chunks}\n\nQuestion: {userQuery}"

    GW->>GW: createUserMessage(augmentedPrompt)
    Note over GW: Plain text UserMessage (no images)

    GW->>Mem: add(conversationId, messages)
    Note over Mem: Augmented prompt saved as user message content

    GW->>LLM: [SystemMessage(role), UserMessage(augmentedPrompt)]
    LLM-->>GW: Response with document context
    GW->>MS: saveAssistantMessage(response)
    GW-->>User: Answer based on PDF content
```

## Follow-Up Message (No Attachments)

```mermaid
sequenceDiagram
    actor User
    participant GW as SpringAIGateway
    participant Mem as ChatMemory
    participant LLM as Chat Model

    User->>GW: Follow-up question (no attachments)

    GW->>GW: processRagIfEnabled()
    Note over GW: No attachments → skip embedding,<br/>return original query unchanged

    GW->>Mem: get(conversationId)
    Note over Mem: Loads history from DB via MessageWindowChatMemory

    Mem-->>GW: History includes:<br/>1. UserMessage (augmented prompt with chunks)<br/>2. AssistantMessage (previous response)

    GW->>GW: Add new UserMessage(followUpQuery)

    GW->>LLM: [System, History(augmented+response), User(followUp)]
    Note over LLM: Model sees PDF context<br/>embedded in prior user message

    LLM-->>GW: Follow-up response
    GW-->>User: Answer using context from history
```

## Key Design Points

1. **Augmented prompt = context persistence** — the RAG-enriched prompt is saved as user
   message content in ChatMemory. Follow-up messages see it in history without re-querying
   VectorStore.

2. **VectorStore active only on first message** — semantic search runs when attachments
   are present. Follow-ups rely on chat history containing the augmented prompt.

3. **Chunking strategy** — `TokenTextSplitter` with 800-token chunks and 100-token overlap
   ensures context continuity across chunk boundaries.

4. **Similarity threshold** (0.7) filters out low-relevance chunks, preventing noise
   in the augmented prompt.

5. **Image-only PDFs follow a different path** — for scanned/image PDFs and local Ollama
   model constraints, see
   [`docs/usecases/image-pdf-vision-cache.md`](./image-pdf-vision-cache.md).
