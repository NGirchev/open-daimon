# Image-Only PDF: Vision Cache Sequence Diagram

> **Fixture test:** `ImagePdfVisionCacheFixtureIT` — run with `./mvnw clean verify -pl opendaimon-app -am -Pfixture`

When a user uploads an image-only PDF (scan, certificate, etc.), the system extracts text
via a vision-capable model and caches it in VectorStore for follow-up queries.

## First Message (PDF Upload)

```mermaid
sequenceDiagram
    actor User
    participant TG as TelegramFileService
    participant GW as SpringAIGateway
    participant DPS as DocumentProcessingService
    participant CS as SpringAIChatService
    participant VS as VectorStore
    participant LLM as Vision Model
    participant DB as ConversationThread
    participant Chat as Chat Model

    User->>TG: Send image-only PDF + question
    TG->>TG: Download file from Telegram, save to MinIO
    TG->>GW: ChatAICommand(text, attachments)

    GW->>GW: processRagIfEnabled()
    GW->>DPS: processPdf(pdfBytes, filename)
    DPS->>DPS: PDFBox: extract text
    DPS-->>GW: throw DocumentContentNotExtractableException

    Note over GW: Fallback: render PDF pages as JPEG images

    GW->>GW: renderPdfToImageAttachments(pdfBytes)

    Note over GW,LLM: Vision Cache: extract text via vision model

    GW->>GW: extractTextFromImagesViaVision()
    GW->>CS: callSimpleVision(visionModel, [UserMessage + Media])
    CS->>LLM: Send images + extraction prompt
    LLM-->>CS: Extracted text content
    CS-->>GW: extractedText

    Note over GW: Vision succeeded — remove images from final message

    GW->>GW: Remove JPEG images from attachments
    GW->>DPS: processExtractedText(extractedText, filename)
    DPS->>DPS: TokenTextSplitter: split into chunks
    DPS->>VS: add(chunks) with metadata type="pdf-vision"
    DPS-->>GW: documentId

    GW->>VS: findAllByDocumentId(documentId)
    VS-->>GW: allChunks

    Note over GW,DB: Store documentId in thread for follow-up RAG

    GW->>DB: Save documentId in memoryBullets
    GW->>GW: Inject RAG context as transient SystemMessage
    GW->>GW: Build UserMessage: original query + placeholder

    GW->>Chat: Send SystemMessage(RAG context) + UserMessage(query + placeholder)
    Chat-->>GW: Response (TEXT model, not VISION)
    GW-->>User: Answer based on extracted text via RAG
```

## Follow-Up Message (No Attachments)

```mermaid
sequenceDiagram
    actor User
    participant GW as SpringAIGateway
    participant DB as ConversationThread
    participant VS as VectorStore
    participant Mem as ChatMemory
    participant Chat as Chat Model

    User->>GW: Follow-up question (no attachments)

    GW->>GW: processRagIfEnabled()
    Note over GW: No attachments — check thread for stored RAG documentIds

    GW->>DB: findByThreadKey(threadKey)
    DB-->>GW: thread.memoryBullets contains [RAG:documentId:uuid:filename:file.pdf]

    GW->>GW: extractRagDocumentIds(memoryBullets)
    GW->>VS: findAllByDocumentId(documentId) — threshold=0.0
    VS-->>GW: allChunks

    GW->>GW: Inject RAG context as transient SystemMessage
    GW->>Mem: get(conversationId) — chat history (placeholder only, no inline RAG text)

    GW->>Chat: Send SystemMessage(fresh RAG context) + chat history + UserMessage(query)
    Chat-->>GW: Response
    GW-->>User: Answer with dynamically retrieved RAG context
```

## Key Design Decisions

1. **RAG context is NOT stored inline in chat memory** — instead, a short placeholder
   `[Documents loaded for context: filename.pdf]` is stored in the UserMessage. The full
   document text lives only in VectorStore, not in `spring_ai_chat_memory`.

2. **DocumentId stored in `ConversationThread.memoryBullets`** — format:
   `[RAG:documentId:<uuid>:filename:<name>]`. On follow-up messages, the gateway reads
   these markers and fetches relevant chunks from VectorStore dynamically.

3. **RAG context injected as transient SystemMessage** — this SystemMessage is added to the
   prompt for the LLM but is NOT persisted by `MessageChatMemoryAdvisor` (which only stores
   User/Assistant messages). This keeps chat memory lean.

4. **After successful vision extraction, images are removed** — the text model (not VISION)
   answers using RAG context. Images are only kept as fallback if vision extraction fails.

5. **Vision extraction is a separate internal call** — uses `callSimpleVision()` without
   ChatMemory, web tools, or conversationId to avoid polluting chat history.

6. **Both first message and follow-up use `findAllByDocumentId()`** — with threshold=0.0
   to bypass cross-language similarity mismatch (e.g. Russian query vs English document).
   Since chunks are filtered by documentId, all returned chunks belong to the user's document.

7. **Graceful degradation on restart** — if VectorStore data is lost (SimpleVectorStore
   is in-memory), follow-up returns no chunks and the model answers from chat history only.
