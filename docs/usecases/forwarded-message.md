# Forwarded (Reposted) Message Handling

> **Fixture test:** `ForwardedMessageFixtureIT` — run with `./mvnw clean verify -pl opendaimon-app -am -Pfixture`

When a user forwards someone else's message to the bot, the system detects the origin,
enriches the text with source attribution, and processes it as a regular message.

## Forwarded Text Message

```mermaid
sequenceDiagram
    actor User
    participant Bot as TelegramBot
    participant MS as TelegramMessageService
    participant CF as AICommandFactory
    participant GW as SpringAIGateway
    participant LLM as Chat Model

    User->>Bot: Forward text message from another user/channel

    Bot->>Bot: onUpdateReceived(update)
    Bot->>Bot: extractForwardInfo(message)

    alt MessageOriginUser
        Bot->>Bot: "John Doe (@johndoe)"
    else MessageOriginChannel
        Bot->>Bot: "Channel Title (Author)"
    else MessageOriginHiddenUser
        Bot->>Bot: "Hidden User Name"
    else MessageOriginChat
        Bot->>Bot: "Chat Title"
    end

    Bot->>Bot: enrichWithForwardContext(text, forwardInfo)
    Note over Bot: "[Forwarded from John Doe (@johndoe)]\n{original text}"

    Note over Bot: Forwarded messages always treated as MESSAGE,<br/>even if text starts with /command

    Bot->>Bot: TelegramCommand(type=MESSAGE,<br/>text=enriched, forwardedFrom=info)

    Bot->>MS: saveUserMessage(enrichedText, attachments=[])
    MS->>CF: createCommand(enrichedText, metadata)
    CF->>GW: ChatAICommand(enrichedText)

    GW->>LLM: [SystemMessage(role), UserMessage(enrichedText)]
    Note over LLM: Model sees source attribution<br/>in the user message

    LLM-->>GW: Response
    GW-->>User: Answer about forwarded content
```

## Forwarded Message with Photo

```mermaid
sequenceDiagram
    actor User
    participant Bot as TelegramBot
    participant FS as TelegramFileService
    participant MS as TelegramMessageService
    participant GW as SpringAIGateway
    participant LLM as Vision Model

    User->>Bot: Forward photo message with caption

    Bot->>Bot: extractForwardInfo(message)
    Bot->>Bot: enrichWithForwardContext(caption, forwardInfo)
    Note over Bot: "[Forwarded from Source]\n{caption}"<br/>If no caption → use default prompt

    Bot->>FS: processPhoto(photoSizes)
    FS->>FS: Download largest photo from Telegram API
    FS->>FS: Save to MinIO
    FS-->>Bot: Attachment(IMAGE, storageKey, data)

    Bot->>Bot: TelegramCommand(type=MESSAGE,<br/>text=enriched, attachments=[photo])

    Bot->>MS: saveUserMessage(enrichedText, [photoAttachment])
    MS->>GW: ChatAICommand(enrichedText, [photo])

    Note over GW: VISION capability required<br/>due to image attachment

    GW->>GW: createUserMessage(text, [Media(image)])
    GW->>LLM: [SystemMessage, UserMessage(text + image)]
    LLM-->>GW: Response about forwarded photo
    GW-->>User: Answer
```

## Forwarded Message with Document (PDF)

```mermaid
sequenceDiagram
    actor User
    participant Bot as TelegramBot
    participant FS as TelegramFileService
    participant GW as SpringAIGateway
    participant DPS as DocumentProcessingService
    participant RAG as FileRAGService
    participant LLM as Chat Model

    User->>Bot: Forward document message

    Bot->>Bot: extractForwardInfo(message)
    Bot->>Bot: enrichWithForwardContext(caption, forwardInfo)
    Note over Bot: "[Forwarded from Source]\n{caption}"

    Bot->>FS: processDocument(document)
    FS->>FS: Download from Telegram, save to MinIO
    FS-->>Bot: Attachment(PDF, storageKey, data)

    Bot->>GW: ChatAICommand(enrichedText, [pdfAttachment])

    GW->>GW: processRagIfEnabled()
    GW->>DPS: processPdf(pdfBytes, filename)

    alt Text-based PDF
        DPS->>DPS: PDFBox extracts text → chunks → VectorStore
        DPS-->>GW: documentId
        GW->>RAG: findRelevantContext(enrichedQuery, documentId)
        RAG-->>GW: relevantChunks
        GW->>GW: createAugmentedPrompt()
    else Image-only PDF
        DPS-->>GW: DocumentContentNotExtractableException
        GW->>GW: renderPdfToImageAttachments()
        GW->>GW: extractTextFromImagesViaVision()
        Note over GW: Vision cache flow<br/>(see image-pdf-vision-cache.md)
    end

    GW->>LLM: Send prompt (augmented or with images)
    LLM-->>GW: Response
    GW-->>User: Answer about forwarded document
```

## Forward Origin Types

| Origin Type | Example | Format |
|-------------|---------|--------|
| **MessageOriginUser** | User forwards from a person | `"John Doe (@johndoe)"` |
| **MessageOriginChannel** | Forward from a channel | `"Channel Title"` or `"Channel Title (Author)"` |
| **MessageOriginHiddenUser** | Privacy-hidden sender | `"Anonymous Name"` |
| **MessageOriginChat** | Forward from a group chat | `"Chat Title"` |

## Key Design Points

1. **Security: forwarded commands are not executed** — even if the forwarded text starts
   with `/start` or any other command, it is treated as a regular MESSAGE. This prevents
   executing arbitrary commands from untrusted sources.

2. **Source attribution in text** — the `[Forwarded from ...]` prefix is embedded directly
   into the user message text, so the LLM always knows the content came from another source.

3. **Localization** — the forward prefix is localized via `telegram.forward.prefix`
   property (`telegram_en.properties` / `telegram_ru.properties`).

4. **Attachments processed normally** — photos, documents, and other attachments from
   forwarded messages go through the same processing pipeline as direct uploads.
