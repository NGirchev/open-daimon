package ru.girchev.aibot.ai.springai.service;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.content.Media;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.UrlResource;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import ru.girchev.aibot.ai.springai.config.RAGProperties;
import ru.girchev.aibot.ai.springai.config.SpringAIModelConfig;
import ru.girchev.aibot.ai.springai.config.SpringAIProperties;
import ru.girchev.aibot.ai.springai.rag.FileRAGService;
import ru.girchev.aibot.ai.springai.retry.SpringAIModelRegistry;
import ru.girchev.aibot.common.ai.ModelCapabilities;
import ru.girchev.aibot.common.ai.command.AIBotChatOptions;
import ru.girchev.aibot.common.ai.command.AICommand;
import ru.girchev.aibot.common.ai.command.ChatAICommand;
import ru.girchev.aibot.common.ai.response.AIResponse;
import ru.girchev.aibot.common.ai.response.SpringAIResponse;
import ru.girchev.aibot.common.service.AIUtils;
import ru.girchev.aibot.common.exception.DocumentContentNotExtractableException;
import ru.girchev.aibot.common.model.Attachment;
import ru.girchev.aibot.common.model.AttachmentType;
import ru.girchev.aibot.common.service.AIGateway;
import ru.girchev.aibot.common.service.AIGatewayRegistry;

import java.net.MalformedURLException;
import java.util.*;
import java.util.Base64;

import static ru.girchev.aibot.common.ai.LlmParamNames.*;

@Getter
@Slf4j
public class SpringAIGateway implements AIGateway {

    /** Ключи формата content parts (OpenAI/OpenRouter): content как массив с type "text" / "image_url". */
    private static final String CONTENT_PART_TYPE = "type";
    private static final String CONTENT_PART_TEXT = "text";
    private static final String CONTENT_PART_IMAGE_URL = "image_url";
    private static final String IMAGE_URL_URL = "url";

    private final SpringAIProperties springAiProperties;
    private final AIGatewayRegistry aiGatewayRegistry;
    private final SpringAIModelRegistry springAIModelRegistry;
    private final SpringAIChatService chatService;
    private final ObjectProvider<ChatMemory> chatMemoryProvider;
    
    // RAG components (optional - available only when ai-bot.ai.spring-ai.rag.enabled=true)
    private final RAGProperties ragProperties;
    private final ObjectProvider<DocumentProcessingService> documentProcessingServiceProvider;
    private final ObjectProvider<FileRAGService> ragServiceProvider;

    public SpringAIGateway(
            SpringAIProperties springAiProperties,
            AIGatewayRegistry aiGatewayRegistry,
            SpringAIModelRegistry springAIModelRegistry,
            SpringAIChatService chatService,
            ObjectProvider<ChatMemory> chatMemoryProvider,
            RAGProperties ragProperties,
            ObjectProvider<DocumentProcessingService> documentProcessingServiceProvider,
            ObjectProvider<FileRAGService> ragServiceProvider) {
        this.springAiProperties = springAiProperties;
        this.aiGatewayRegistry = aiGatewayRegistry;
        this.springAIModelRegistry = springAIModelRegistry;
        this.chatService = chatService;
        this.chatMemoryProvider = chatMemoryProvider;
        this.ragProperties = ragProperties;
        this.documentProcessingServiceProvider = documentProcessingServiceProvider;
        this.ragServiceProvider = ragServiceProvider;
    }

    @PostConstruct
    public void init() {
        aiGatewayRegistry.registerAiGateway(this);
    }

    @Override
    public boolean supports(AICommand command) {
        return !springAIModelRegistry.getCandidatesByCapabilities(command.modelCapabilities(), null).isEmpty();
    }

    @Override
    public AIResponse generateResponse(AICommand command) {
        if (Boolean.TRUE.equals(springAiProperties.getMock())) {
            return createMockResponse();
        }

        try {
            if (command.options() instanceof AIBotChatOptions chatOptions) {
                List<Message> messages = createMessages(chatOptions.body());
                log.info("Gateway: messagesFromBody={}, userRole='{}'", messages.size(), chatOptions.userRole());
                
                // ВАЖНО: body с ключом MESSAGES может содержать историю из ConversationHistoryAICommandFactory,
                // но ТОЛЬКО если ai-bot.common.manual-conversation-history.enabled=true.
                // Для local профиля (enabled=false) ConversationHistoryAICommandFactory отключена,
                // поэтому body.messages будет пустым и используется DefaultAICommandFactory.
                // Когда enabled=true, ConversationHistoryAICommandFactory добавляет историю + текущий User запрос в body.messages.
                // Поэтому нужно проверять дубликаты перед добавлением system/user сообщений.
                
                // Важно: текущие system/user должны быть добавлены всегда (как было раньше),
                // но без дублей, если ConversationHistoryAICommandFactory уже положила их в messages.
                if (StringUtils.hasText(chatOptions.systemRole())) {
                    String systemRole = chatOptions.systemRole();
                    boolean alreadyPresent = messages.stream()
                            .filter(SystemMessage.class::isInstance)
                            .map(SystemMessage.class::cast)
                            .anyMatch(m -> systemRole.equals(m.getText()));
                    if (!alreadyPresent) {
                        // System message should be first in the prompt.
                        messages.addFirst(new SystemMessage(systemRole));
                    }
                }
                if (StringUtils.hasText(chatOptions.userRole())) {
                    String userRole = chatOptions.userRole();
                    // Проверяем, есть ли уже User сообщение с таким же текстом в списке
                    boolean alreadyPresent = messages.stream()
                            .filter(UserMessage.class::isInstance)
                            .map(UserMessage.class::cast)
                            .anyMatch(m -> userRole.equals(m.getText()));
                    List<Attachment> attachments = (command instanceof ChatAICommand chatCommand) 
                            ? chatCommand.attachments() 
                            : List.of();
                    log.info("Gateway: addingUserMessage={}, attachmentsCount={}, commandIsChatAICommand={}",
                            !alreadyPresent, attachments != null ? attachments.size() : 0, command instanceof ChatAICommand);
                    if (!alreadyPresent) {
                        // Создаём мутабельный список для возможного добавления image-attachment'ов из PDF fallback
                        List<Attachment> mutableAttachments = new ArrayList<>(attachments);
                        
                        // Считаем оригинальные IMAGE-вложения до обработки RAG
                        long originalImageCount = attachments.stream()
                                .filter(att -> att.type() == AttachmentType.IMAGE)
                                .count();
                        
                        // RAG: обработка PDF attachments
                        List<String> pdfAsImageFilenames = new ArrayList<>();
                        String finalUserRole = processRagIfEnabled(userRole, mutableAttachments, pdfAsImageFilenames);
                        
                        // Дополнительный системный промпт с контекстом вложений
                        String attachmentContext = buildAttachmentContextMessage((int) originalImageCount, pdfAsImageFilenames);
                        if (attachmentContext != null) {
                            SystemMessage attachmentSystemMessage = new SystemMessage(attachmentContext);
                            messages.add(attachmentSystemMessage);
                            
                            // Явно сохраняем системное сообщение о вложениях в ChatMemory,
                            // чтобы в режиме Spring AI ChatMemory (manual-conversation-history.enabled=false)
                            // контекст вложений поднимался на последующих шагах диалога.
                            ChatMemory chatMemory = chatMemoryProvider != null ? chatMemoryProvider.getIfAvailable() : null;
                            if (chatMemory != null && command != null && command.metadata() != null) {
                                Object threadKey = command.metadata().get(AICommand.THREAD_KEY_FIELD);
                                if (threadKey != null) {
                                    chatMemory.add(String.valueOf(threadKey), attachmentSystemMessage);
                                }
                            }
                        }
                        
                        messages.add(createUserMessage(finalUserRole, mutableAttachments));
                    }
                }

                List<SpringAIModelConfig> candidates = springAIModelRegistry.getCandidatesByCapabilities(command.modelCapabilities(), null);
                if (candidates.isEmpty()) {
                    candidates = springAIModelRegistry.getCandidatesByCapabilities(Set.of(ModelCapabilities.AUTO), null);
                }
                SpringAIModelConfig modelConfig = candidates.isEmpty()
                        ? null
                        : candidates.getFirst();
                if (modelConfig == null) {
                    throw new RuntimeException("No model found for capabilities: " + command.modelCapabilities());
                }

                if (chatOptions.stream()) {
                    return chatService.streamChat(
                            modelConfig,
                            command,
                            chatOptions,
                            messages);
                } else {
                    return chatService.callChat(
                            modelConfig,
                            command,
                            chatOptions,
                            messages
                    );
                }
            } else {
                throw new IllegalArgumentException();
            }
        } catch (WebClientResponseException e) {
            log.error("Error calling Spring AI ChatModel: {}", e.getMessage());
            throw new RuntimeException("Failed to generate response from Spring AI", e);
        } catch (DocumentContentNotExtractableException e) {
            throw e;
        } catch (Exception e) {
            if (AIUtils.shouldLogWithoutStacktrace(e)) {
                log.error("Error calling Spring AI ChatModel: {}", AIUtils.getRootCauseMessage(e));
            } else {
                log.error("Error calling Spring AI ChatModel: {}", e.getMessage(), e);
            }
            throw new RuntimeException("Failed to generate response from Spring AI", e);
        }
    }

    @Override
    public AIResponse generateResponse(Map<String, Object> requestBody) {
        if (Boolean.TRUE.equals(springAiProperties.getMock())) {
            return createMockResponse();
        }

        try {
            List<Message> messages = createMessages(requestBody);
            String modelName = extractModelFromMap(requestBody);
            if (!StringUtils.hasText(modelName)) {
                throw new IllegalArgumentException("Model name is required in request body");
            }
            var modelConfigOpt = springAIModelRegistry.getByModelName(modelName);
            SpringAIModelConfig modelConfig;
            if (modelConfigOpt.isPresent()) {
                modelConfig = modelConfigOpt.get();
            } else {
                Set<ModelCapabilities> caps = Set.of(ModelCapabilities.AUTO);
                List<SpringAIModelConfig> fallback = springAIModelRegistry.getCandidatesByCapabilities(caps, null);
                if (fallback.isEmpty()) {
                    throw new IllegalArgumentException("Unknown model: " + modelName);
                }
                log.warn("Model not found in registry, using first candidate by capabilities. requested={}, using={}", modelName, fallback.getFirst().getName());
                modelConfig = fallback.getFirst();
            }
            return chatService.callChatFromBody(
                    modelConfig,
                    requestBody,
                    requestBody.get(AICommand.THREAD_KEY_FIELD),
                    true,
                    messages
            );
        } catch (WebClientResponseException e) {
            log.error("Error calling Spring AI ChatModel: {}", e.getMessage());
            throw new RuntimeException("Failed to generate response from Spring AI", e);
        } catch (DocumentContentNotExtractableException e) {
            throw e;
        } catch (Exception e) {
            if (AIUtils.shouldLogWithoutStacktrace(e)) {
                log.error("Error calling Spring AI ChatModel: {}", AIUtils.getRootCauseMessage(e));
            } else {
                log.error("Error calling Spring AI ChatModel: {}", e.getMessage(), e);
            }
            throw new RuntimeException("Failed to generate response from Spring AI", e);
        }
    }

    private List<Message> createMessages(Map<String, Object> requestBody) {
        @SuppressWarnings("unchecked")
        var messagesList = (List<Map<String, Object>>) requestBody.get(MESSAGES);
        if (messagesList == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(messagesList.stream()
                .map(this::messageFromMap)
                .toList());
    }

    /**
     * Строит Spring AI Message из элемента messages (OpenAI/OpenRouter).
     * Поддерживает content как строку и как массив content parts (type "text", "image_url").
     */
    private Message messageFromMap(Map<String, Object> msg) {
        String role = (String) msg.get(ROLE);
        Object content = msg.get(CONTENT);
        if (content == null) {
            content = "";
        }
        if (content instanceof String text) {
            return switch (role) {
                case ROLE_SYSTEM -> new SystemMessage(text);
                case ROLE_USER -> new UserMessage(text);
                case ROLE_ASSISTANT -> new AssistantMessage(text);
                default -> throw new IllegalArgumentException("Unsupported role: " + role);
            };
        }
        if (content instanceof List<?> parts) {
            ContentParts parsed = parseContentParts(parts);
            return switch (role) {
                case ROLE_SYSTEM -> new SystemMessage(parsed.text());
                case ROLE_ASSISTANT -> new AssistantMessage(parsed.text());
                case ROLE_USER -> parsed.media().isEmpty()
                        ? new UserMessage(parsed.text())
                        : UserMessage.builder().text(parsed.text()).media(parsed.media()).build();
                default -> throw new IllegalArgumentException("Unsupported role: " + role);
            };
        }
        throw new IllegalArgumentException("content must be String or List of content parts");
    }

    /**
     * Парсит массив content parts (OpenAI/OpenRouter): type "text" и type "image_url".
     */
    private ContentParts parseContentParts(List<?> parts) {
        StringBuilder text = new StringBuilder();
        List<Media> media = new ArrayList<>();
        for (Object p : parts) {
            if (!(p instanceof Map<?, ?> partMap)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> part = (Map<String, Object>) partMap;
            String type = (String) part.get(CONTENT_PART_TYPE);
            if (CONTENT_PART_TEXT.equals(type)) {
                String t = (String) part.get(CONTENT_PART_TEXT);
                if (t != null) text.append(t);
            } else if (CONTENT_PART_IMAGE_URL.equals(type)) {
                Media m = mediaFromImageUrlPart(part);
                if (m != null) media.add(m);
            }
        }
        return new ContentParts(text.toString().trim(), List.copyOf(media));
    }

    private Media mediaFromImageUrlPart(Map<String, Object> part) {
        Object imageUrlObj = part.get(CONTENT_PART_IMAGE_URL);
        String url;
        if (imageUrlObj instanceof Map<?, ?> urlMap) {
            url = (String) ((Map<String, ?>) urlMap).get(IMAGE_URL_URL);
        } else if (imageUrlObj instanceof String s) {
            url = s;
        } else {
            return null;
        }
        if (url == null || url.isBlank()) return null;
        return mediaFromImageUrl(url);
    }

    /**
     * Создаёт Media из URL: data:image/...;base64,... или https://...
     */
    private Media mediaFromImageUrl(String url) {
        if (url.startsWith("data:")) {
            int comma = url.indexOf(',');
            if (comma == -1) return null;
            String header = url.substring(5, comma);
            String dataB64 = url.substring(comma + 1);
            String mimeType = "image/png";
            if (header.contains(";")) {
                mimeType = header.substring(0, header.indexOf(';')).trim();
            } else if (!header.isBlank()) {
                mimeType = header.trim();
            }
            byte[] data = Base64.getDecoder().decode(dataB64);
            return new Media(MimeTypeUtils.parseMimeType(mimeType), new ByteArrayResource(data));
        }
        try {
            return new Media(MimeTypeUtils.parseMimeType("image/png"), new UrlResource(url));
        } catch (MalformedURLException e) {
            log.warn("Invalid image URL, skipping: {}", url);
            return null;
        }
    }

    private record ContentParts(String text, List<Media> media) {}

    private AIResponse createMockResponse() {
        return new SpringAIResponse(
                ChatResponse.builder()
                        .generations(List.of(new Generation(new AssistantMessage("Mocked response"))))
                        .build()
        );
    }

    /**
     * Извлекает имя модели из body.
     * Модель может быть указана в ключе MODEL или в OPTIONS.MODEL.
     *
     * @param body body из команды
     * @return имя модели или null, если не указана
     */
    private String extractModelFromMap(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return null;
        }

        // Проверяем прямой ключ MODEL
        Object model = body.get(MODEL);
        if (model instanceof String) {
            return (String) model;
        }

        // Проверяем вложенный OPTIONS.MODEL
        @SuppressWarnings("unchecked")
        Map<String, Object> options = (Map<String, Object>) body.get(OPTIONS);
        if (options != null) {
            Object optionsModel = options.get(MODEL);
            if (optionsModel instanceof String) {
                return (String) optionsModel;
            }
        }

        return null;
    }

    /**
     * Обрабатывает документы (PDF, DOCX и др.) через RAG если feature flag включен.
     * 
     * <p>Процесс:
     * <ol>
     *   <li>Фильтрует документы (PDF, DOCX и др.)</li>
     *   <li>Обрабатывает каждый документ через DocumentProcessingService (создает embeddings)</li>
     *   <li>Ищет релевантный контекст через RAGService</li>
     *   <li>Создает augmented prompt с контекстом из документов</li>
     * </ol>
     *
     * <p><b>Vision Fallback для image-only PDF:</b>
     * Если PDF не содержит текстового слоя (DocumentContentNotExtractableException),
     * рендерим страницы как изображения и добавляем их в attachments для vision-модели.
     *
     * @param userQuery оригинальный запрос пользователя
     * @param attachments мутабельный список вложений (может быть дополнен image-attachment'ами из PDF fallback)
     * @param pdfAsImageFilenames мутабельный список для сбора имён PDF, сконвертированных в изображения
     * @return augmented prompt с контекстом из документов (или оригинальный запрос если RAG выключен)
     */
    private String processRagIfEnabled(String userQuery, List<Attachment> attachments, List<String> pdfAsImageFilenames) {
        int totalAttachments = attachments != null ? attachments.size() : 0;
        List<Attachment> documentAttachments = attachments != null
                ? attachments.stream().filter(Attachment::isDocument).toList()
                : List.of();
        var documentProcessingService = documentProcessingServiceProvider.getIfAvailable();
        FileRAGService fileRagService = ragServiceProvider.getIfAvailable();
        log.info("processRagIfEnabled: userQuery='{}', totalAttachments={}, documentAttachments={}, ragPropertiesNull={}, docServiceNull={}, ragServiceNull={}",
                userQuery, totalAttachments, documentAttachments.size(), ragProperties == null, documentProcessingService == null, fileRagService == null);

        // Проверяем feature flag
        if (ragProperties == null || !isRagEnabled()) {
            return userQuery;
        }

        if (documentAttachments.isEmpty()) {
            log.info("processRagIfEnabled: skipped, no document attachments (Attachment.isDocument false for all?)");
            return userQuery;
        }

        if (documentProcessingService == null || fileRagService == null) {
            log.warn("RAG is enabled but services are not available. Skipping RAG processing.");
            return userQuery;
        }

        log.info("Processing {} document attachment(s) for RAG", documentAttachments.size());
        
        // Обрабатываем документы и собираем контекст
        List<Document> allRelevantChunks = new ArrayList<>();
        
        for (Attachment documentAttachment : documentAttachments) {
            try {
                String mimeType = documentAttachment.mimeType() != null 
                        ? documentAttachment.mimeType().toLowerCase() 
                        : "";
                // 1. Обрабатываем документ через ETL Pipeline
                String documentType = extractDocumentType(mimeType, documentAttachment.filename());
                int dataLength = documentAttachment.data() != null ? documentAttachment.data().length : 0;
                log.info("processRagIfEnabled: processing document filename={}, mimeType={}, dataLength={}, documentType={}",
                        documentAttachment.filename(), mimeType, dataLength, documentType);
                if (documentType == null) {
                    log.warn("Unsupported document type for RAG: {}", mimeType);
                    continue;
                }

                String documentId;
                // PDF обрабатывается через PagePdfDocumentReader (PDFBox) - специализированный ридер для лучшего качества
                if ("pdf".equalsIgnoreCase(documentType)) {
                    try {
                        documentId = documentProcessingService.processPdf(
                                documentAttachment.data(), 
                                documentAttachment.filename()
                        );
                    } catch (DocumentContentNotExtractableException e) {
                        // PDF не содержит текстового слоя (скан/сертификат) - используем vision fallback
                        log.info("PDF '{}' has no text layer, rendering pages as images for vision model", documentAttachment.filename());
                        List<Attachment> imageAttachments = renderPdfToImageAttachments(
                                documentAttachment.data(), 
                                documentAttachment.filename()
                        );
                        attachments.addAll(imageAttachments);
                        pdfAsImageFilenames.add(documentAttachment.filename());
                        log.info("Added {} image attachment(s) from PDF '{}' for vision model", 
                                imageAttachments.size(), documentAttachment.filename());
                        // Пропускаем RAG-обработку для этого документа, продолжаем со следующим
                        continue;
                    }
                } else {
                    // Все остальные форматы через TikaDocumentReader (DOCX, DOC, XLS, XLSX, PPT, PPTX, TXT и др.)
                    documentId = documentProcessingService.processWithTika(
                            documentAttachment.data(), 
                            documentAttachment.filename(),
                            documentType
                    );
                }
                
                // 2. Ищем релевантный контекст
                List<Document> relevantChunks = fileRagService.findRelevantContext(userQuery, documentId);
                allRelevantChunks.addAll(relevantChunks);
                log.info("processRagIfEnabled: documentId={}, relevantChunks={}", documentId, relevantChunks.size());
                log.debug("Found {} relevant chunks from '{}'", relevantChunks.size(), documentAttachment.filename());
            } catch (Exception e) {
                // Документ не содержит извлекаемого текста — пробрасываем дальше и не вызываем модель
                if (e instanceof DocumentContentNotExtractableException docEx) {
                    throw docEx;
                }
                log.error("Failed to process document '{}': {}", documentAttachment.filename(), e.getMessage(), e);
                // Продолжаем с остальными документами
            }
        }
        
        if (allRelevantChunks.isEmpty()) {
            log.info("No relevant context found in documents");
            return userQuery;
        }
        
        // 3. Создаем augmented prompt
        String augmentedPrompt = fileRagService.createAugmentedPrompt(userQuery, allRelevantChunks);
        log.info("Created augmented prompt with {} relevant chunks from {} document(s)", 
                allRelevantChunks.size(), documentAttachments.size());
        
        return augmentedPrompt;
    }

    /**
     * Проверяет, включен ли RAG feature flag.
     */
    private boolean isRagEnabled() {
        // RAGProperties создается только когда rag.enabled=true (через @ConditionalOnProperty)
        // Но для безопасности проверяем дополнительно
        return ragProperties != null;
    }

    /**
     * Маппинг паттернов MIME типов и расширений файлов к типам документов.
     * PDF проверяется первым, так как обрабатывается через PDFBox, а не через Tika.
     */
    private static final List<DocumentTypeMapping> DOCUMENT_TYPE_MAPPINGS = List.of(
            // PDF - проверяется первым, обрабатывается через PDFBox (PagePdfDocumentReader), не через Tika
            new DocumentTypeMapping("pdf", List.of("pdf"), List.of(".pdf")),
            // Word документы
            new DocumentTypeMapping("docx", List.of("wordprocessingml"), List.of(".docx")),
            new DocumentTypeMapping("doc", List.of("msword"), List.of(".doc")),
            // Excel документы
            new DocumentTypeMapping("xlsx", List.of("spreadsheetml"), List.of(".xlsx")),
            new DocumentTypeMapping("xls", List.of("ms-excel"), List.of(".xls")),
            // PowerPoint документы
            new DocumentTypeMapping("pptx", List.of("presentationml"), List.of(".pptx")),
            new DocumentTypeMapping("ppt", List.of("ms-powerpoint"), List.of(".ppt")),
            // Текстовые файлы
            new DocumentTypeMapping("txt", List.of("text/plain"), List.of(".txt")),
            // Rich Text Format
            new DocumentTypeMapping("rtf", List.of("rtf"), List.of(".rtf")),
            // OpenDocument Format (альтернатива MS Office)
            new DocumentTypeMapping("odt", List.of("opendocument.text"), List.of(".odt")),
            new DocumentTypeMapping("ods", List.of("opendocument.spreadsheet"), List.of(".ods")),
            new DocumentTypeMapping("odp", List.of("opendocument.presentation"), List.of(".odp")),
            // CSV
            new DocumentTypeMapping("csv", List.of("csv"), List.of(".csv")),
            // HTML
            new DocumentTypeMapping("html", List.of("text/html"), List.of(".html", ".htm")),
            // Markdown
            new DocumentTypeMapping("md", List.of("markdown"), List.of(".md", ".markdown")),
            // JSON
            new DocumentTypeMapping("json", List.of("json"), List.of(".json")),
            // XML
            new DocumentTypeMapping("xml", List.of("xml"), List.of(".xml")),
            // EPUB (электронные книги)
            new DocumentTypeMapping("epub", List.of("epub"), List.of(".epub"))
    );

    /**
     * Извлекает тип документа из MIME типа или имени файла.
     * 
     * <p><b>Важно:</b> PDF определяется первым и обрабатывается через PagePdfDocumentReader (PDFBox),
     * а не через TikaDocumentReader, для лучшего качества извлечения текста.
     * 
     * @param mimeType MIME тип файла
     * @param filename имя файла
     * @return тип документа (pdf, docx, doc, xls, xlsx, ppt, pptx, txt, rtf, odt, ods, odp, csv, html, md, json, xml, epub) или null если не поддерживается
     */
    private String extractDocumentType(String mimeType, String filename) {
        if (mimeType == null && filename == null) {
            return null;
        }
        
        String type = mimeType != null ? mimeType.toLowerCase() : "";
        String name = filename != null ? filename.toLowerCase() : "";
        
        return DOCUMENT_TYPE_MAPPINGS.stream()
                .filter(mapping -> mapping.matches(type, name))
                .map(DocumentTypeMapping::documentType)
                .findFirst()
                .orElse(null);
    }

    /**
     * Вспомогательный класс для маппинга паттернов к типам документов.
     */
    private record DocumentTypeMapping(
            String documentType,
            List<String> mimeTypePatterns,
            List<String> fileExtensions
    ) {
        /**
         * Проверяет, соответствует ли MIME тип или имя файла данному маппингу.
         */
        boolean matches(String mimeType, String filename) {
            boolean mimeMatches = mimeTypePatterns.stream()
                    .anyMatch(mimeType::contains);
            boolean extensionMatches = fileExtensions.stream()
                    .anyMatch(filename::endsWith);
            return mimeMatches || extensionMatches;
        }
    }

    /**
     * Формирует системное сообщение с контекстом о вложениях.
     *
     * @param originalImageCount количество оригинальных IMAGE-вложений (до PDF fallback)
     * @param pdfAsImageFilenames список имён PDF, сконвертированных в изображения
     * @return текст системного сообщения или null если нет вложений
     */
    private String buildAttachmentContextMessage(int originalImageCount, List<String> pdfAsImageFilenames) {
        boolean hasImages = originalImageCount > 0;
        boolean hasPdfAsImages = !pdfAsImageFilenames.isEmpty();
        
        if (!hasImages && !hasPdfAsImages) {
            return null;
        }
        
        StringBuilder context = new StringBuilder();
        
        // PDF-документы, представленные в виде изображений
        if (hasPdfAsImages) {
            if (pdfAsImageFilenames.size() == 1) {
                context.append("Пользователь приложил PDF-документ \"")
                        .append(pdfAsImageFilenames.get(0))
                        .append("\", который представлен в виде изображений.");
            } else {
                context.append("Пользователь приложил PDF-документы ");
                for (int i = 0; i < pdfAsImageFilenames.size(); i++) {
                    if (i > 0) {
                        context.append(", ");
                    }
                    context.append("\"").append(pdfAsImageFilenames.get(i)).append("\"");
                }
                context.append(", которые представлены в виде изображений.");
            }
        }
        
        // Обычные изображения
        if (hasImages) {
            if (hasPdfAsImages) {
                context.append("\n");
            }
            if (originalImageCount == 1) {
                context.append("Пользователь прикрепил изображение.");
            } else {
                context.append("Пользователь прикрепил изображения (")
                        .append(originalImageCount)
                        .append(" шт.).");
            }
        }
        
        return context.toString();
    }

    /**
     * Создает UserMessage с поддержкой multimodal (изображения).
     * Если нет изображений, возвращает обычный текстовый UserMessage.
     *
     * @param content текст сообщения
     * @param attachments список вложений
     * @return UserMessage (с Media или без)
     */
    private Message createUserMessage(String content, List<Attachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return new UserMessage(content);
        }
        
        // Фильтруем только изображения
        List<Media> mediaList = attachments.stream()
                .filter(att -> att.type() == AttachmentType.IMAGE)
                .map(this::toMedia)
                .toList();
        
        if (mediaList.isEmpty()) {
            return new UserMessage(content);
        }
        
        log.debug("Creating multimodal UserMessage with {} image(s)", mediaList.size());
        return UserMessage.builder()
                .text(content)
                .media(mediaList)
                .build();
    }

    /**
     * Конвертирует Attachment в Spring AI Media.
     *
     * @param attachment вложение
     * @return Media объект для Spring AI
     */
    private Media toMedia(Attachment attachment) {
        var mimeType = MimeTypeUtils.parseMimeType(attachment.mimeType());
        var resource = new ByteArrayResource(attachment.data());
        return new Media(mimeType, resource);
    }

    /**
     * Рендерит страницы PDF в изображения для обработки vision-моделью.
     * 
     * <p>Используется как fallback когда PDF не содержит текстового слоя (скан/сертификат).
     * 
     * @param pdfData байты PDF документа
     * @param filename оригинальное имя файла
     * @return список Attachment с type=IMAGE для каждой страницы
     */
    private List<Attachment> renderPdfToImageAttachments(byte[] pdfData, String filename) {
        try {
            org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdfData);
            org.apache.pdfbox.rendering.PDFRenderer renderer = new org.apache.pdfbox.rendering.PDFRenderer(document);
            
            int pageCount = document.getNumberOfPages();
            int maxPages = 10;
            int pagesToRender = Math.min(pageCount, maxPages);
            
            if (pageCount > maxPages) {
                log.warn("PDF '{}' has {} pages, rendering only first {} pages for vision model", 
                        filename, pageCount, maxPages);
            }
            
            List<Attachment> imageAttachments = new ArrayList<>();
            
            for (int pageIndex = 0; pageIndex < pagesToRender; pageIndex++) {
                java.awt.image.BufferedImage image = renderer.renderImageWithDPI(pageIndex, 200);
                
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(image, "JPEG", baos);
                byte[] imageBytes = baos.toByteArray();
                
                String imageFilename = String.format("page_%d_%s.jpg", pageIndex + 1, 
                        filename.replaceAll("\\.pdf$", ""));
                
                Attachment imageAttachment = new Attachment(
                        null,
                        "image/jpeg",
                        imageFilename,
                        imageBytes.length,
                        AttachmentType.IMAGE,
                        imageBytes
                );
                imageAttachments.add(imageAttachment);
            }
            
            document.close();
            log.info("Rendered {} pages from PDF '{}' as images for vision", pagesToRender, filename);
            return imageAttachments;
            
        } catch (Exception e) {
            log.error("Failed to render PDF '{}' pages as images", filename, e);
            return List.of();
        }
    }

}
