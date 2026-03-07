package ru.girchev.aibot.ai.springai.service;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.content.Media;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import ru.girchev.aibot.ai.springai.config.RAGProperties;
import ru.girchev.aibot.ai.springai.config.SpringAIModelConfig;
import ru.girchev.aibot.ai.springai.config.SpringAIProperties;
import ru.girchev.aibot.common.ai.ModelType;
import ru.girchev.aibot.common.ai.command.AIBotChatOptions;
import ru.girchev.aibot.common.ai.command.AICommand;
import ru.girchev.aibot.common.ai.command.ChatAICommand;
import ru.girchev.aibot.common.ai.response.AIResponse;
import ru.girchev.aibot.common.ai.response.SpringAIResponse;
import ru.girchev.aibot.common.model.Attachment;
import ru.girchev.aibot.common.model.AttachmentType;
import ru.girchev.aibot.common.service.AIGateway;
import ru.girchev.aibot.common.service.AIGatewayRegistry;

import java.util.*;

import static ru.girchev.aibot.common.ai.LlmParamNames.*;

@Getter
@Slf4j
public class SpringAIGateway implements AIGateway {

    private final SpringAIProperties springAiProperties;
    private final AIGatewayRegistry aiGatewayRegistry;
    private final SpringAIModelType springAIModelType;
    private final SpringAIChatService chatService;
    
    // RAG components (optional - available only when ai-bot.ai.spring-ai.rag.enabled=true)
    private final RAGProperties ragProperties;
    private final ObjectProvider<DocumentProcessingService> documentProcessingServiceProvider;
    private final ObjectProvider<RAGService> ragServiceProvider;

    public SpringAIGateway(
            SpringAIProperties springAiProperties,
            AIGatewayRegistry aiGatewayRegistry,
            SpringAIModelType springAIModelType,
            SpringAIChatService chatService,
            RAGProperties ragProperties,
            ObjectProvider<DocumentProcessingService> documentProcessingServiceProvider,
            ObjectProvider<RAGService> ragServiceProvider) {
        this.springAiProperties = springAiProperties;
        this.aiGatewayRegistry = aiGatewayRegistry;
        this.springAIModelType = springAIModelType;
        this.chatService = chatService;
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
        return springAIModelType.getByCapabilities(command.modelTypes()).isPresent();
    }

    @Override
    public AIResponse generateResponse(AICommand command) {
        if (Boolean.TRUE.equals(springAiProperties.getMock())) {
            return createMockResponse();
        }

        try {
            if (command.options() instanceof AIBotChatOptions chatOptions) {
                List<Message> messages = createMessages(chatOptions.body());
                log.trace("Messages size: {}", messages.size());
                
                // ВАЖНО: body с ключом MESSAGES может содержать историю из ConversationHistoryAICommandFactory,
                // но ТОЛЬКО если ai-bot.common.conversation-context.enabled=true.
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
                    if (!alreadyPresent) {
                        // Извлекаем attachments из ChatAICommand (если это ChatAICommand)
                        List<Attachment> attachments = (command instanceof ChatAICommand chatCommand) 
                                ? chatCommand.attachments() 
                                : List.of();
                        
                        // RAG: обработка PDF attachments
                        String finalUserRole = processRagIfEnabled(userRole, attachments);
                        
                        messages.add(createUserMessage(finalUserRole, attachments));
                    }
                }

                SpringAIModelConfig modelConfig = springAIModelType.getByCapabilities(command.modelTypes())
                        .or(() -> springAIModelType.getByCapabilities(Set.of(ModelType.AUTO)))
                        .orElseThrow(() -> new RuntimeException("No model found for capabilities: " + command.modelTypes()));

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
        } catch (Exception e) {
            log.error("Error calling Spring AI ChatModel: {}", e.getMessage(), e);
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
            SpringAIModelConfig modelConfig = modelName != null
                    ? springAIModelType.getByModelName(modelName).orElse(null)
                    : null;
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
        } catch (Exception e) {
            log.error("Error calling Spring AI ChatModel: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to generate response from Spring AI", e);
        }
    }

    private List<Message> createMessages(Map<String, Object> requestBody) {
        @SuppressWarnings("unchecked")
        var messagesList = ((List<Map<String, String>>) requestBody.get(MESSAGES));
        if (messagesList == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(messagesList.stream()
                .map(msg -> {
                    String role = msg.get(ROLE);
                    String content = msg.get(CONTENT);
                    var message = switch (role) {
                        case ROLE_SYSTEM -> new SystemMessage(content);
                        case ROLE_USER -> new UserMessage(content);
                        case ROLE_ASSISTANT -> new AssistantMessage(content);
                        default -> throw new IllegalArgumentException("Not supported");
                    };
                    return ((Message) message);
                })
                .toList());
    }

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
     * @param userQuery оригинальный запрос пользователя
     * @param attachments список вложений
     * @return augmented prompt с контекстом из документов (или оригинальный запрос если RAG выключен)
     */
    private String processRagIfEnabled(String userQuery, List<Attachment> attachments) {
        // Проверяем feature flag
        if (ragProperties == null || !isRagEnabled()) {
            return userQuery;
        }
        
        // Фильтруем документы (PDF, DOCX и др.)
        List<Attachment> documentAttachments = attachments.stream()
                .filter(Attachment::isDocument)
                .toList();
        
        if (documentAttachments.isEmpty()) {
            return userQuery;
        }
        
        DocumentProcessingService documentProcessingService = documentProcessingServiceProvider.getIfAvailable();
        RAGService ragService = ragServiceProvider.getIfAvailable();
        
        if (documentProcessingService == null || ragService == null) {
            log.warn("RAG is enabled but services are not available. Skipping RAG processing.");
            return userQuery;
        }
        
        log.info("Processing {} document attachment(s) for RAG", documentAttachments.size());
        
        // Обрабатываем документы и собираем контекст
        List<Document> allRelevantChunks = new ArrayList<>();
        
        for (Attachment documentAttachment : documentAttachments) {
            try {
                String documentId;
                String mimeType = documentAttachment.mimeType() != null 
                        ? documentAttachment.mimeType().toLowerCase() 
                        : "";
                
                // 1. Обрабатываем документ через ETL Pipeline
                String documentType = extractDocumentType(mimeType, documentAttachment.filename());
                if (documentType == null) {
                    log.warn("Unsupported document type for RAG: {}", mimeType);
                    continue;
                }
                
                // PDF обрабатывается через PagePdfDocumentReader (PDFBox) - специализированный ридер для лучшего качества
                if ("pdf".equalsIgnoreCase(documentType)) {
                    documentId = documentProcessingService.processPdf(
                            documentAttachment.data(), 
                            documentAttachment.filename()
                    );
                } else {
                    // Все остальные форматы через TikaDocumentReader (DOCX, DOC, XLS, XLSX, PPT, PPTX, TXT и др.)
                    documentId = documentProcessingService.processWithTika(
                            documentAttachment.data(), 
                            documentAttachment.filename(),
                            documentType
                    );
                }
                
                // 2. Ищем релевантный контекст
                List<Document> relevantChunks = ragService.findRelevantContext(userQuery, documentId);
                allRelevantChunks.addAll(relevantChunks);
                
                log.debug("Found {} relevant chunks from '{}'", relevantChunks.size(), documentAttachment.filename());
            } catch (Exception e) {
                log.error("Failed to process document '{}': {}", documentAttachment.filename(), e.getMessage(), e);
                // Продолжаем с остальными документами
            }
        }
        
        if (allRelevantChunks.isEmpty()) {
            log.info("No relevant context found in documents");
            return userQuery;
        }
        
        // 3. Создаем augmented prompt
        String augmentedPrompt = ragService.createAugmentedPrompt(userQuery, allRelevantChunks);
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

}
