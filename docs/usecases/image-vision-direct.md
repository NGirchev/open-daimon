# Direct Image Vision: JPEG/PNG Processing

> **Manual tests:**
> - `ObjectsImageVisionOllamaManualIT`, `ObjectsImageVisionOpenRouterManualIT` — photo of objects
> - `GreekImageVisionOllamaManualIT`, `GreekImageVisionOpenRouterManualIT` — image with Greek text
>
> Run with: `./mvnw -pl opendaimon-app -am clean test-compile failsafe:integration-test failsafe:verify -Dit.test=<TestClass> -Dfailsafe.failIfNoSpecifiedTests=false -Dmanual.ollama.e2e=true`

When a user uploads a JPEG/PNG image (not a PDF), the system sends it directly to a
vision-capable model as a `Media` object. **No RAG indexing is performed** — images bypass
the document processing pipeline entirely. Follow-up questions rely on conversation history
(ChatMemory), not VectorStore.

## First Message (Image Upload + Question)

```mermaid
sequenceDiagram
    actor User
    participant TG as TelegramFileService
    participant MS as MessageService
    participant GW as SpringAIGateway
    participant Mem as ChatMemory
    participant LLM as Vision Model

    User->>TG: Send JPEG/PNG image + question
    TG->>TG: Download file from Telegram API
    TG->>TG: Save to MinIO (storageKey)
    TG->>MS: Attachment(storageKey, "image/jpeg", filename, data, IMAGE)

    MS->>MS: saveUserMessage(text, attachments)
    Note over MS: Persists to DB with sequenceNumber

    MS->>GW: ChatAICommand(userQuery, attachments=[IMAGE], metadata={})

    GW->>GW: addSystemAndUserMessagesIfNeeded()

    Note over GW: Images bypass RAG — processRagIfEnabled() filters<br/>only document attachments (isDocument()), not IMAGE type

    GW->>GW: Count originalImageCount (IMAGE attachments)
    GW->>GW: addAttachmentContextToMessagesAndMemory()
    Note over GW,Mem: Add SystemMessage: "User attached image(s)"<br/>stored in ChatMemory for follow-up context

    GW->>GW: createUserMessage(query, attachments)
    GW->>GW: Filter attachments: att.type() == AttachmentType.IMAGE
    GW->>GW: toMedia(attachment) for each image
    Note over GW: MimeType + ByteArrayResource → Media object

    GW->>GW: UserMessage.builder().text(query).media(mediaList).build()

    Note over GW,LLM: Model selection: VISION capability required

    GW->>GW: hasUserMedia(messages) → requiresVisionForPayload = true
    GW->>GW: AUTO mode: remove AUTO, add VISION to required capabilities
    GW->>GW: Select model with VISION capability (e.g. gemma3:4b)

    GW->>LLM: [SystemMessage(role/lang), UserMessage(query + Media)]
    LLM-->>GW: Vision model response (describes image content)
    GW->>MS: saveAssistantMessage(response)
    GW-->>User: Answer based on image analysis
```

## Follow-Up Message (No Attachments)

```mermaid
sequenceDiagram
    actor User
    participant Handler as MessageHandler
    participant MS as MessageService
    participant GW as SpringAIGateway
    participant Mem as ChatMemory
    participant LLM as Chat Model

    User->>Handler: Follow-up question (no attachments)

    Handler->>MS: Check thread for ragDocumentIds
    MS-->>Handler: No ragDocumentIds (images are not indexed in RAG)

    Handler->>GW: ChatAICommand(text, [], metadata={})

    GW->>GW: processFollowUpRagIfAvailable()
    Note over GW: No documentIds in metadata — skip RAG retrieval

    GW->>Mem: get(conversationId)
    Note over Mem: Loads history from DB via MessageWindowChatMemory<br/>Includes previous UserMessage (with image description)<br/>and AssistantMessage (vision model response)

    GW->>GW: hasUserMedia(messages) → false (no images in follow-up)
    GW->>GW: Select TEXT-capable model (VISION not required)

    GW->>LLM: [SystemMessage(role/lang), History, UserMessage(followUp)]
    Note over LLM: Model answers from conversation history only<br/>(no RAG context, no image re-analysis)

    LLM-->>GW: Follow-up response
    GW-->>User: Answer based on conversation history
```

## Key Design Points

1. **No RAG for direct images** — `processRagIfEnabled()` filters attachments via
   `Attachment.isDocument()`, which returns `false` for `AttachmentType.IMAGE`. Images go
   straight to the vision model without chunking, embedding, or VectorStore indexing.

2. **VISION capability auto-detection** — `hasUserMedia(messages)` checks if any
   `UserMessage` contains `Media` objects. If true, `ModelCapabilities.VISION` is added to
   required capabilities, and `ModelCapabilities.AUTO` is removed to force vision model
   selection.

3. **Follow-up uses conversation history, not RAG** — since images are not indexed, follow-up
   questions rely entirely on `ChatMemory` (previous user/assistant messages). The model must
   infer context from the conversation, not from VectorStore chunks.

4. **Model switch between turns** — the first message uses a VISION-capable model (e.g.
   `gemma3:4b`), but the follow-up may use a different TEXT-only model (e.g. `qwen2.5:3b`)
   since no images are present. The conversation history bridges the context gap.

5. **Attachment context in ChatMemory** — `addAttachmentContextToMessagesAndMemory()` adds a
   `SystemMessage` ("User attached image(s)") to `ChatMemory`, helping the follow-up model
   understand that an image was previously discussed.

6. **Media conversion** — `toMedia(attachment)` converts `Attachment.data()` (byte array) into
   a Spring AI `Media` object with the correct MIME type (`image/jpeg`, `image/png`, etc.) and
   a `ByteArrayResource`. This is passed to `UserMessage.builder().media(mediaList)`.

## Comparison with Other Attachment Flows

| Aspect | Direct Image (this doc) | Text PDF | Image-Only PDF | DOC/XLS |
|--------|------------------------|----------|----------------|---------|
| AttachmentType | `IMAGE` | `PDF` | `PDF` | `PDF` |
| Text Extraction | None (vision direct) | PDFBox | Vision OCR fallback | Tika |
| RAG Indexed | **No** | Yes | Yes | Yes |
| Follow-Up Context | ChatMemory only | VectorStore + ChatMemory | VectorStore + ChatMemory | VectorStore + ChatMemory |
| Model Required | VISION | CHAT | CHAT + VISION (OCR) | CHAT |
| Related Doc | — | [text-pdf-rag.md](./text-pdf-rag.md) | [image-pdf-vision-cache.md](./image-pdf-vision-cache.md) | [doc-xls-tika-rag.md](./doc-xls-tika-rag.md) |
