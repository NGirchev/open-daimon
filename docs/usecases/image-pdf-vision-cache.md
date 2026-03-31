# Image-Only PDF: Vision Cache Sequence Diagram

> **Fixture test:** `ImagePdfVisionCacheFixtureIT` — run with `./mvnw clean verify -pl opendaimon-app -am -Pfixture`
>
> **Manual tests:**
> - `ImagePdfVisionRagOllamaManualIT` — `image-based-pdf-sample.pdf` with OCR via gemma3:4b
>
> Run with: `./mvnw -pl opendaimon-app -am clean test-compile failsafe:integration-test failsafe:verify -Dit.test=ImagePdfVisionRagOllamaManualIT -Dfailsafe.failIfNoSpecifiedTests=false -Dmanual.ollama.e2e=true`

When a user uploads an image-only PDF (scan, certificate, etc.), the system detects it
before the gateway call, renders pages as images, extracts text via a vision-capable model,
and caches it in VectorStore for follow-up queries.

## First Message (PDF Upload)

```mermaid
sequenceDiagram
    actor User
    participant TG as TelegramFileService
    participant PL as AIRequestPipeline
    participant OR as SpringDocumentOrchestrator
    participant AN as SpringDocumentContentAnalyzer
    participant PD as PdfTextDetector
    participant PR as SpringDocumentPreprocessor
    participant DPS as DocumentProcessingService
    participant CS as SpringAIChatService
    participant VS as VectorStore
    participant CF as DefaultAICommandFactory
    participant GW as SpringAIGateway
    participant LLM as Vision Model
    participant Chat as Chat Model

    User->>TG: Send image-only PDF + question
    TG->>TG: Download file from Telegram, save to MinIO
    TG->>PL: IChatCommand(text, attachments, metadata={})

    PL->>OR: orchestrate(command)

    OR->>AN: analyze(attachment)
    AN->>PD: hasExtractableText(pdfBytes)
    PD-->>AN: false — no text layer
    AN-->>OR: DocumentAnalysisResult(IMAGE_ONLY, requires VISION)

    Note over OR,PR: Preprocessing: render pages + vision OCR

    OR->>PR: preprocess(attachment, userQuery, IMAGE_ONLY)

    PR->>PR: renderPdfToImageAttachments(pdfBytes)
    Note over PR: PDF pages → preprocessed PNG images (300 DPI)

    PR->>CS: callSimpleVision(visionModel, [UserMessage + Media])
    CS->>LLM: Send images + extraction prompt
    LLM-->>CS: Extracted text content
    CS-->>PR: extractedText

    Note over PR: Vision succeeded — images not needed for final message

    PR->>DPS: processExtractedText(extractedText, filename)
    DPS->>DPS: TokenTextSplitter: split into chunks
    DPS->>VS: add(chunks) with metadata type="pdf-vision"
    DPS-->>PR: documentId

    PR-->>OR: DocumentPreprocessingResult(documentId, allChunks, imageAttachments=[], visionSucceeded=true)

    OR->>OR: storeDocumentIdsInCommandMetadata(documentId, command)
    Note over OR: metadata.put(RAG_DOCUMENT_IDS_FIELD, documentId)
    OR->>OR: Build RAG context prefix from allChunks
    OR-->>PL: OrchestratedChatCommand(RAG prefix + query, attachments=[], metadata)

    PL->>CF: createCommand(orchestratedCommand)
    Note over CF: No IMAGE attachments (vision OCR succeeded, images removed)<br/>→ no VISION added to capabilities
    CF-->>PL: ChatAICommand(CHAT capability)

    PL->>GW: ChatAICommand(RAG prefix + query, CHAT)

    GW->>GW: Model selection: CHAT capability → TEXT model selected (not VISION)
    GW->>Chat: Send SystemMessage(role/lang) + UserMessage(RAG prefix + query + placeholder)
    Chat-->>GW: Response (TEXT model)
    GW-->>User: Answer based on extracted text via RAG

    Note over GW: Handler reads metadata, persists ragDocumentIds to USER message
```

## Vision OCR Fallback (OCR Failed)

If vision extraction fails, the PDF page images are kept as attachments so the model can
process them directly. In this case the factory detects IMAGE attachments and adds VISION.

```mermaid
sequenceDiagram
    participant PR as SpringDocumentPreprocessor
    participant CF as DefaultAICommandFactory
    participant GW as SpringAIGateway
    participant LLM as Vision Model

    PR->>PR: renderPdfToImageAttachments(pdfBytes)
    PR->>PR: extractTextFromImagesViaVision() → fails / extraction incomplete
    PR-->>PR: visionExtractionSucceeded = false

    Note over PR: Images kept as attachments for direct vision processing

    PR-->>CF: OrchestratedChatCommand(originalQuery, attachments=[IMAGE, IMAGE, ...])

    CF->>CF: attachments contain IMAGE → add VISION to required capabilities
    CF-->>GW: ChatAICommand(CHAT + VISION, attachments=[IMAGE])

    GW->>GW: Model selection: CHAT + VISION required → VISION model selected
    GW->>LLM: [SystemMessage, UserMessage(query + Media)]
    LLM-->>GW: Vision model response
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
    participant Chat as Chat Model

    User->>Handler: Follow-up question (no attachments)

    Handler->>MS: findRagDocumentIds(thread)
    MS-->>Handler: List<documentId> from USER message metadata

    Handler->>Handler: metadata.put(RAG_DOCUMENT_IDS_FIELD, documentIds)
    Handler->>PL: IChatCommand(text, [], metadata={ragDocumentIds=...})

    PL->>OR: processFollowUpRagIfAvailable(command)
    Note over OR: No attachments — read documentIds from command.metadata()

    OR->>OR: command.metadata().get(RAG_DOCUMENT_IDS_FIELD)
    OR->>VS: findAllByDocumentId(documentId) — threshold=0.0
    VS-->>OR: allChunks

    OR->>OR: Build RAG context prefix from chunks
    OR-->>PL: OrchestratedChatCommand(RAG prefix + followUp)

    PL->>CF: createCommand(orchestratedCommand)
    CF-->>PL: ChatAICommand(CHAT capability)

    PL->>GW: ChatAICommand(RAG prefix + followUp)

    GW->>Mem: get(conversationId) — chat history (User/Assistant turns)

    GW->>Chat: Send SystemMessage(role/lang) + chat history + UserMessage(RAG prefix + query)
    Chat-->>GW: Response
    GW-->>User: Answer with dynamically retrieved RAG context
```

## Key Design Decisions

1. **Vision capability detection before gateway** — `SpringDocumentContentAnalyzer` (via
   `PdfTextDetector`) determines whether a PDF needs VISION before model selection. This
   ensures REGULAR users who lack VISION access are blocked at the factory level, not deep
   inside the gateway.

2. **RAG context is prepended to UserMessage** — the orchestrator builds a RAG prefix from
   retrieved chunks and prepends it to the user query. A short placeholder
   `[Documents loaded for context: filename.pdf]` is also appended for traceability.

3. **DocumentId stored in USER message metadata** — the orchestrator writes documentIds into
   `AICommand.metadata` under `RAG_DOCUMENT_IDS_FIELD`. The handler then persists them
   on the USER message via `OpenDaimonMessageService.updateRagMetadata()`. On follow-up
   messages, the handler reads stored documentIds from message history and injects them
   back into `AICommand.metadata` before calling the pipeline.

4. **No transient RAG SystemMessage** — document context is injected directly into
   `UserMessage` as a prefix so small local models reliably consume it.

5. **After successful vision extraction, images are removed** — the text model (not VISION)
   answers using RAG context. Images are only kept as fallback if vision extraction fails.

6. **Vision extraction is a separate internal call** — `SpringDocumentPreprocessor` uses
   `callSimpleVision()` without ChatMemory, web tools, or conversationId to avoid polluting
   chat history.

   **Note:** Direct JPEG/PNG images (not wrapped in PDF) follow a completely different path —
   they go straight to the vision model without OCR extraction or RAG indexing. See
   [`docs/usecases/image-vision-direct.md`](./image-vision-direct.md).

7. **Both first message and follow-up use `findAllByDocumentId()`** — with threshold=0.0
   to bypass cross-language similarity mismatch (e.g. Russian query vs English document).
   Since chunks are filtered by documentId, all returned chunks belong to the user's document.

8. **Graceful degradation on restart** — if VectorStore data is lost (SimpleVectorStore
   is in-memory), follow-up returns no chunks and the model answers from chat history only.

## Direct Ollama Findings (Local Validation, March 29, 2026)

These findings were validated with direct `POST /api/chat` calls to local Ollama using the
same PDF sample from IT resources.

1. **`gemma3:4b` is the viable vision OCR path** — direct image OCR works when the page is
   sent as a lossless PNG (300 DPI) with a full extraction prompt and deterministic options
   (`temperature=0`, `top_p=1`, fixed `seed`, high `num_predict`).

2. **Two-step dialog is reproducible with `gemma3:4b` vision input** — asking first
   `"что в первом предложении?"` and then
   `"а что было в последнем предложении в скобках?"`
   returns the expected phrase `(as far as they know)` in repeated direct runs.

3. **`gemma3:1b` should not be used for image input** — local direct calls with image payload
   returned HTTP 500:
   `"this model is missing data required for image input"`.

4. **`gemma3:1b` is valid for text-only follow-up (RAG-style)** — when OCR text is already
   available as plain context, `gemma3:1b` can answer follow-up correctly and include
   `(as far as they know)`.

5. **Operational model split** — for this local setup, reliable image-PDF flow is:
   vision OCR on `gemma3:4b` -> store text in RAG -> follow-up on text model (`gemma3:1b`
   or another text-capable model).

## Prompting Caveat

Direct narrow prompts such as "return only the final parentheses phrase" were less reliable
than full-page OCR extraction prompts. The robust sequence is:

1. Extract full page text via vision.
2. Ask follow-up question over extracted text context.
