package io.github.ngirchev.opendaimon.ai.springai.service;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
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
import io.github.ngirchev.opendaimon.ai.springai.config.RAGProperties;
import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIModelConfig;
import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIProperties;
import io.github.ngirchev.opendaimon.ai.springai.rag.FileRAGService;
import io.github.ngirchev.opendaimon.ai.springai.retry.SpringAIModelRegistry;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.ai.command.OpenDaimonChatOptions;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.command.ChatAICommand;
import io.github.ngirchev.opendaimon.common.ai.command.FixedModelChatAICommand;
import io.github.ngirchev.opendaimon.common.ai.response.AIResponse;
import io.github.ngirchev.opendaimon.common.ai.response.SpringAIResponse;
import io.github.ngirchev.opendaimon.common.service.AIUtils;
import io.github.ngirchev.opendaimon.common.exception.DocumentContentNotExtractableException;
import io.github.ngirchev.opendaimon.common.exception.UnsupportedModelCapabilityException;
import io.github.ngirchev.opendaimon.common.model.Attachment;
import io.github.ngirchev.opendaimon.common.model.AttachmentType;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.repository.ConversationThreadRepository;
import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.common.service.AIGateway;
import io.github.ngirchev.opendaimon.common.service.AIGatewayRegistry;

import java.awt.image.BufferedImage;
import java.net.MalformedURLException;
import java.util.*;
import java.util.Base64;

import static io.github.ngirchev.opendaimon.common.ai.LlmParamNames.*;

@Getter
@Slf4j
public class SpringAIGateway implements AIGateway {

    /** Content part keys (OpenAI/OpenRouter): content as array with type "text" / "image_url". */
    private static final String CONTENT_PART_TYPE = "type";
    private static final String CONTENT_PART_TEXT = "text";
    private static final String CONTENT_PART_IMAGE_URL = "image_url";
    private static final String IMAGE_URL_URL = "url";
    private static final String LOG_ERROR_CALLING_SPRING_AI = "Error calling Spring AI ChatModel: {}";

    private final SpringAIProperties springAiProperties;
    private final AIGatewayRegistry aiGatewayRegistry;
    private final SpringAIModelRegistry springAIModelRegistry;
    private final SpringAIChatService chatService;
    private final ObjectProvider<ChatMemory> chatMemoryProvider;
    
    // RAG components (optional - available only when open-daimon.ai.spring-ai.rag.enabled=true)
    private final RAGProperties ragProperties;
    private final ObjectProvider<DocumentProcessingService> documentProcessingServiceProvider;
    private final ObjectProvider<FileRAGService> ragServiceProvider;
    private final ObjectProvider<ConversationThreadRepository> conversationThreadRepositoryProvider;

    /** Prefix for RAG document references stored in ConversationThread.memoryBullets. */
    static final String RAG_BULLET_PREFIX = "[RAG:documentId:";
    static final String RAG_BULLET_FILENAME_SEPARATOR = ":filename:";

    public SpringAIGateway(
            SpringAIProperties springAiProperties,
            AIGatewayRegistry aiGatewayRegistry,
            SpringAIModelRegistry springAIModelRegistry,
            SpringAIChatService chatService,
            ObjectProvider<ChatMemory> chatMemoryProvider,
            RAGProperties ragProperties,
            ObjectProvider<DocumentProcessingService> documentProcessingServiceProvider,
            ObjectProvider<FileRAGService> ragServiceProvider,
            ObjectProvider<ConversationThreadRepository> conversationThreadRepositoryProvider) {
        this.springAiProperties = springAiProperties;
        this.aiGatewayRegistry = aiGatewayRegistry;
        this.springAIModelRegistry = springAIModelRegistry;
        this.chatService = chatService;
        this.chatMemoryProvider = chatMemoryProvider;
        this.ragProperties = ragProperties;
        this.documentProcessingServiceProvider = documentProcessingServiceProvider;
        this.ragServiceProvider = ragServiceProvider;
        this.conversationThreadRepositoryProvider = conversationThreadRepositoryProvider;
    }

    @PostConstruct
    public void init() {
        aiGatewayRegistry.registerAiGateway(this);
    }

    @Override
    public boolean supports(AICommand command) {
        if (command instanceof FixedModelChatAICommand fixed) {
            return springAIModelRegistry.getByModelName(fixed.fixedModelId()).isPresent();
        }
        return !springAIModelRegistry.getCandidatesByCapabilities(command.modelCapabilities(), null).isEmpty();
    }

    @Override
    public AIResponse generateResponse(AICommand command) {
        if (Boolean.TRUE.equals(springAiProperties.getMock())) {
            return createMockResponse();
        }

        try {
            if (command.options() instanceof OpenDaimonChatOptions chatOptions) {
                List<Message> messages = createMessages(chatOptions.body());
                log.debug("Gateway: messagesFromBody={}, userRole='{}'", messages.size(), chatOptions.userRole() == null ? null : chatOptions.userRole().replaceAll("\\s+", " ").trim());
                addSystemAndUserMessagesIfNeeded(messages, chatOptions, command);
                return executeChatWithOptions(chatOptions, command, messages);
            } else {
                throw new IllegalArgumentException();
            }
        } catch (WebClientResponseException e) {
            log.error(LOG_ERROR_CALLING_SPRING_AI, e.getMessage());
            throw new RuntimeException("Failed to generate response from Spring AI", e);
        } catch (UnsupportedModelCapabilityException e) {
            throw e;
        } catch (DocumentContentNotExtractableException e) {
            throw e;
        } catch (Exception e) {
            if (AIUtils.shouldLogWithoutStacktrace(e)) {
                log.error(LOG_ERROR_CALLING_SPRING_AI, AIUtils.getRootCauseMessage(e));
            } else {
                log.error(LOG_ERROR_CALLING_SPRING_AI, e.getMessage(), e);
            }
            throw new RuntimeException("Failed to generate response from Spring AI", e);
        }
    }

    private AIResponse executeChatWithOptions(OpenDaimonChatOptions chatOptions, AICommand command, List<Message> messages) {
        UserPriority userPriority = resolveUserPriority(command);
        boolean requiresVisionForPayload = hasUserMedia(messages);

        SpringAIModelConfig modelConfig;

        if (command instanceof FixedModelChatAICommand fixed) {
            // User explicitly selected a model — use it directly, bypass capability filter
            modelConfig = springAIModelRegistry.getByModelName(fixed.fixedModelId())
                    .orElseThrow(() -> new RuntimeException("Selected model not found in registry: " + fixed.fixedModelId()));

            // Second-line guard: validate live registry capabilities at execution time
            Set<ModelCapabilities> liveCapabilities = modelConfig.getCapabilities() != null
                    ? modelConfig.getCapabilities() : Set.of();
            Set<ModelCapabilities> requiredCapabilities = new HashSet<>(command.modelCapabilities().stream()
                    .filter(c -> c != ModelCapabilities.AUTO)
                    .collect(java.util.stream.Collectors.toSet()));
            if (requiresVisionForPayload) {
                requiredCapabilities.add(ModelCapabilities.VISION);
            }
            if (!requiredCapabilities.isEmpty() && !liveCapabilities.containsAll(requiredCapabilities)) {
                Set<ModelCapabilities> missing = requiredCapabilities.stream()
                        .filter(c -> !liveCapabilities.contains(c))
                        .collect(java.util.stream.Collectors.toSet());
                throw new UnsupportedModelCapabilityException(fixed.fixedModelId(), missing);
            }
        } else {
            // AUTO mode — use capability-based selection
            Set<ModelCapabilities> requiredForSelection = new HashSet<>(command.modelCapabilities());
            if (requiresVisionForPayload) {
                // AUTO is a meta-capability ("auto-select best model"), not a real model capability.
                // VISION models typically don't declare AUTO, so requiring both yields no candidates.
                requiredForSelection.remove(ModelCapabilities.AUTO);
                requiredForSelection.add(ModelCapabilities.VISION);
            }
            List<SpringAIModelConfig> candidates = springAIModelRegistry
                    .getCandidatesByCapabilities(requiredForSelection, null, userPriority);
            candidates = preferTextOnlyModelsForTextPayload(candidates, requiresVisionForPayload);
            // Prefer models that also cover optional capabilities (stable sort — preserves priority order within same score)
            Set<ModelCapabilities> optional = command.optionalCapabilities();
            if (!optional.isEmpty() && !candidates.isEmpty()) {
                candidates = candidates.stream()
                        .sorted(Comparator.comparingInt(
                                (SpringAIModelConfig m) -> -countMatchingCaps(m.getCapabilities(), optional)))
                        .toList();
            }
            modelConfig = candidates.isEmpty() ? null : candidates.getFirst();
            if (modelConfig == null) {
                throw new RuntimeException("No model found for capabilities: " + requiredForSelection);
            }
            Set<ModelCapabilities> liveCapabilities = modelConfig.getCapabilities() != null
                    ? modelConfig.getCapabilities() : Set.of();
            Set<ModelCapabilities> effectiveRequired = requiredForSelection.stream()
                    .filter(c -> c != ModelCapabilities.AUTO)
                    .collect(java.util.stream.Collectors.toSet());
            if (!effectiveRequired.isEmpty() && !liveCapabilities.containsAll(effectiveRequired)) {
                Set<ModelCapabilities> missing = effectiveRequired.stream()
                        .filter(c -> !liveCapabilities.contains(c))
                        .collect(java.util.stream.Collectors.toSet());
                throw new UnsupportedModelCapabilityException(modelConfig.getName(), missing);
            }
        }

        log.info("Selected model='{}', provider={}, caps={}",
                modelConfig.getName(), modelConfig.getProviderType(), modelConfig.getCapabilities());

        if (modelConfig.getProviderType() == null) {
            throw new IllegalStateException(
                    "Model from registry has null providerType. model=" + modelConfig.getName()
                            + ". Ensure open-daimon.ai.spring-ai.models.list entries have provider-type: OPENAI or OLLAMA.");
        }
        if (chatOptions.stream()) {
            return chatService.streamChat(modelConfig, command, chatOptions, messages);
        }
        return chatService.callChat(modelConfig, command, chatOptions, messages);
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
            var modelConfig = springAIModelRegistry.getByModelName(modelName)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown model: " + modelName));
            return chatService.callChatFromBody(
                    modelConfig,
                    requestBody,
                    requestBody.get(AICommand.THREAD_KEY_FIELD),
                    true,
                    messages
            );
        } catch (WebClientResponseException e) {
            log.error(LOG_ERROR_CALLING_SPRING_AI, e.getMessage());
            throw new RuntimeException("Failed to generate response from Spring AI", e);
        } catch (DocumentContentNotExtractableException e) {
            throw e;
        } catch (Exception e) {
            if (AIUtils.shouldLogWithoutStacktrace(e)) {
                log.error(LOG_ERROR_CALLING_SPRING_AI, AIUtils.getRootCauseMessage(e));
            } else {
                log.error(LOG_ERROR_CALLING_SPRING_AI, e.getMessage(), e);
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
     * Builds Spring AI Message from messages element (OpenAI/OpenRouter).
     * Supports content as string and as array of content parts (type "text", "image_url").
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
     * Parses content parts array (OpenAI/OpenRouter): type "text" and type "image_url".
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
     * Creates Media from URL: data:image/...;base64,... or https://...
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

    private void addSystemAndUserMessagesIfNeeded(List<Message> messages, OpenDaimonChatOptions chatOptions, AICommand command) {
        if (StringUtils.hasText(chatOptions.systemRole())) {
            String systemRole = appendLanguageInstruction(chatOptions.systemRole(), command);
            boolean alreadyPresent = messages.stream()
                    .filter(SystemMessage.class::isInstance)
                    .map(SystemMessage.class::cast)
                    .anyMatch(m -> systemRole.equals(m.getText()));
            if (!alreadyPresent) {
                messages.addFirst(new SystemMessage(systemRole));
            }
        }
        if (!StringUtils.hasText(chatOptions.userRole())) {
            return;
        }
        String userRole = chatOptions.userRole();
        boolean userAlreadyPresent = messages.stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .anyMatch(m -> userRole.equals(m.getText()));
        List<Attachment> attachments = command instanceof ChatAICommand chatCommand
                ? chatCommand.attachments()
                : command instanceof FixedModelChatAICommand fixed
                        ? fixed.attachments()
                        : List.of();
        log.debug("Gateway: addingUserMessage={}, attachmentsCount={}, commandType={}",
                !userAlreadyPresent, attachments != null ? attachments.size() : 0, command.getClass().getSimpleName());
        if (userAlreadyPresent) {
            return;
        }
        List<Attachment> mutableAttachments = new ArrayList<>(attachments);
        long originalImageCount = attachments.stream().filter(att -> att.type() == AttachmentType.IMAGE).count();
        List<String> pdfAsImageFilenames = new ArrayList<>();
        String finalUserRole = processRagIfEnabled(userRole, mutableAttachments, pdfAsImageFilenames, messages, command);
        addAttachmentContextToMessagesAndMemory(messages, (int) originalImageCount, pdfAsImageFilenames, command);
        messages.add(createUserMessage(finalUserRole, mutableAttachments));
    }

    private void addAttachmentContextToMessagesAndMemory(List<Message> messages, int originalImageCount,
                                                          List<String> pdfAsImageFilenames, AICommand command) {
        String attachmentContext = buildAttachmentContextMessage(originalImageCount, pdfAsImageFilenames);
        if (attachmentContext == null) {
            return;
        }
        SystemMessage attachmentSystemMessage = new SystemMessage(attachmentContext);
        messages.add(attachmentSystemMessage);
        ChatMemory chatMemory = chatMemoryProvider != null ? chatMemoryProvider.getIfAvailable() : null;
        if (chatMemory != null && command != null && command.metadata() != null) {
            Object threadKey = command.metadata().get(AICommand.THREAD_KEY_FIELD);
            if (threadKey != null) {
                chatMemory.add(String.valueOf(threadKey), attachmentSystemMessage);
            }
        }
    }

    /**
     * Extracts model name from body.
     * Model may be in key MODEL or in OPTIONS.MODEL.
     *
     * @param body command body
     * @return model name or null if not set
     */
    private String extractModelFromMap(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return null;
        }

        // Check direct MODEL key
        Object model = body.get(MODEL);
        if (model instanceof String) {
            return (String) model;
        }

        // Check nested OPTIONS.MODEL
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

    private String appendLanguageInstruction(String systemRole, AICommand command) {
        if (command == null || command.metadata() == null) {
            return systemRole;
        }
        String languageCode = command.metadata().get(AICommand.LANGUAGE_CODE_FIELD);
        if (languageCode == null || languageCode.isBlank()) {
            return systemRole;
        }
        String languageName = switch (languageCode.toLowerCase()) {
            case "ru" -> "Russian";
            case "en" -> "English";
            case "de" -> "German";
            case "fr" -> "French";
            case "es" -> "Spanish";
            case "zh" -> "Chinese";
            default -> languageCode;
        };
        return systemRole + "\nIMPORTANT: Always respond in " + languageName + " (" + languageCode + ").";
    }

    private UserPriority resolveUserPriority(AICommand command) {
        if (command == null || command.metadata() == null) {
            return null;
        }
        String raw = command.metadata().get(AICommand.USER_PRIORITY_FIELD);
        if (raw == null) {
            return null;
        }
        try {
            return UserPriority.valueOf(raw);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown userPriority in command metadata: {}", raw);
            return null;
        }
    }

    /**
     * Processes documents (PDF, DOCX, etc.) via RAG when feature flag is on.
     *
     * <p>Process:
     * <ol>
     *   <li>Filter documents (PDF, DOCX, etc.)</li>
     *   <li>Process each via DocumentProcessingService (embeddings)</li>
     *   <li>Find relevant context via RAGService</li>
     *   <li>Build augmented prompt with document context</li>
     * </ol>
     * <p><b>Vision fallback for image-only PDF:</b> If PDF has no text layer (DocumentContentNotExtractableException), render pages as images and add to attachments for vision model.
     *
     * @param userQuery original user query
     * @param attachments mutable list of attachments (may get image-attachments from PDF fallback)
     * @param pdfAsImageFilenames mutable list to collect PDF filenames converted to images
     * @param messages mutable list of messages — RAG context is injected as transient SystemMessage
     * @param command AI command with metadata (threadKey for looking up stored documentIds)
     * @return user query (original or with placeholder reference); RAG context goes into SystemMessage, not here
     */
    private String processRagIfEnabled(String userQuery, List<Attachment> attachments, List<String> pdfAsImageFilenames,
                                        List<Message> messages, AICommand command) {
        int totalAttachments = attachments != null ? attachments.size() : 0;
        List<Attachment> documentAttachments = attachments != null
                ? attachments.stream().filter(Attachment::isDocument).toList()
                : List.of();
        var documentProcessingService = documentProcessingServiceProvider.getIfAvailable();
        FileRAGService fileRagService = ragServiceProvider.getIfAvailable();
        // Check feature flag
        if (ragProperties == null || !isRagEnabled()) {
            return userQuery;
        }

        if (documentAttachments.isEmpty()) {
            // No new documents — check if thread has stored RAG documentIds for follow-up
            return processFollowUpRagIfAvailable(userQuery, messages, command, fileRagService);
        }

        log.debug("RAG: Processing new document attachments, chunking and indexing to VectorStore");

        // Defensive fallback: if query is blank but documents are present, use a default summarization prompt
        if (userQuery == null || userQuery.isBlank()) {
            userQuery = "Summarize this document and provide key points.";
            log.info("processRagIfEnabled: empty user query with attachments, using default summarization prompt");
        }

        log.info("processRagIfEnabled: totalAttachments={}, documentAttachments={}", totalAttachments, documentAttachments.size());

        if (documentProcessingService == null || fileRagService == null) {
            log.warn("RAG is enabled but services are not available. Skipping RAG processing.");
            return userQuery;
        }

        log.info("Processing {} document attachment(s) for RAG", documentAttachments.size());
        List<Document> allRelevantChunks = new ArrayList<>();
        List<String> processedDocumentIds = new ArrayList<>();
        for (Attachment documentAttachment : documentAttachments) {
            try {
                processOneDocumentForRag(documentAttachment, userQuery, documentProcessingService, fileRagService,
                        allRelevantChunks, attachments, pdfAsImageFilenames, processedDocumentIds);
            } catch (DocumentContentNotExtractableException e) {
                throw e;
            } catch (Exception e) {
                log.error("Failed to process document '{}': {}", documentAttachment.filename(), e.getMessage(), e);
            }
        }

        // Store documentIds in thread memoryBullets for follow-up RAG lookups
        storeDocumentIdsInThread(processedDocumentIds, documentAttachments, command);

        if (allRelevantChunks.isEmpty()) {
            log.info("No relevant context found in documents");
            return userQuery;
        }

        // Build RAG context as prefix for user query (not SystemMessage — small models ignore it)
        String ragPrefix = buildRagContextPrefix(allRelevantChunks);
        String placeholder = buildRagPlaceholder(documentAttachments);
        log.info("Created RAG context prefix ({} chars) with {} chunks from {} document(s); UserMessage gets placeholder",
                ragPrefix.length(), allRelevantChunks.size(), documentAttachments.size());

        return ragPrefix + userQuery + "\n" + placeholder;
    }

    /**
     * On follow-up messages (no new attachments), checks if the thread has stored RAG documentIds
     * in memoryBullets and fetches relevant chunks from VectorStore.
     */
    private String processFollowUpRagIfAvailable(String userQuery, List<Message> messages,
                                                   AICommand command, FileRAGService fileRagService) {
        if (fileRagService == null || command == null || command.metadata() == null) {
            log.debug("RAG: No attachments, no thread context available");
            return userQuery;
        }

        String threadKey = command.metadata().get(AICommand.THREAD_KEY_FIELD);
        if (threadKey == null) {
            log.debug("RAG: No threadKey in command metadata, skipping follow-up RAG");
            return userQuery;
        }

        ConversationThreadRepository threadRepo = conversationThreadRepositoryProvider != null
                ? conversationThreadRepositoryProvider.getIfAvailable() : null;
        if (threadRepo == null) {
            log.debug("RAG: ConversationThreadRepository not available, skipping follow-up RAG");
            return userQuery;
        }

        Optional<ConversationThread> threadOpt = threadRepo.findByThreadKey(threadKey);
        if (threadOpt.isEmpty()) {
            log.debug("RAG: Thread not found for threadKey={}", threadKey);
            return userQuery;
        }

        ConversationThread thread = threadOpt.get();
        List<String> ragDocumentIds = extractRagDocumentIds(thread.getMemoryBullets());
        if (ragDocumentIds.isEmpty()) {
            log.debug("RAG: No stored RAG documentIds in thread memoryBullets for threadKey={}", threadKey);
            return userQuery;
        }

        log.info("RAG follow-up: found {} stored documentId(s) in thread, fetching relevant chunks", ragDocumentIds.size());

        List<Document> allChunks = new ArrayList<>();
        for (String docId : ragDocumentIds) {
            // Use findAllByDocumentId (threshold=0.0) to bypass cross-language similarity mismatch
            // (e.g. Russian query vs English extracted text would fail with threshold=0.7)
            List<Document> chunks = fileRagService.findAllByDocumentId(docId);
            allChunks.addAll(chunks);
        }

        if (allChunks.isEmpty()) {
            log.info("RAG follow-up: VectorStore returned no chunks for stored documentIds (may be lost after restart)");
            return userQuery;
        }

        String ragPrefix = buildRagContextPrefix(allChunks);
        log.info("RAG follow-up: prepended {} relevant chunks as user query prefix ({} chars)", allChunks.size(), ragPrefix.length());

        return ragPrefix + userQuery;
    }

    /**
     * Template for RAG document context injected as a prefix to the user query.
     * Prepended directly to the UserMessage (not a separate SystemMessage) because
     * small local models (e.g. qwen2.5:3b) may ignore system messages, especially
     * when ChatMemoryAdvisor loads conversation history.
     */
    private static final String RAG_USER_CONTEXT_TEMPLATE =
            "Below is content extracted from the user's uploaded document(s). " +
            "Use this context to answer the question that follows.\n\n" +
            "--- Document context ---\n%s\n--- End of document context ---\n\n";

    /**
     * Builds a RAG context prefix to be prepended to the user query.
     * Using user message prefix (not SystemMessage) ensures small models reliably
     * see the context even when ChatMemoryAdvisor loads conversation history.
     */
    private String buildRagContextPrefix(List<Document> chunks) {
        String contextText = chunks.stream()
                .map(Document::getText)
                .collect(java.util.stream.Collectors.joining("\n\n---\n\n"));
        return String.format(RAG_USER_CONTEXT_TEMPLATE, contextText);
    }

    /**
     * Stores processed documentIds in thread's memoryBullets for follow-up RAG lookups.
     */
    private void storeDocumentIdsInThread(List<String> documentIds, List<Attachment> documentAttachments,
                                           AICommand command) {
        if (documentIds.isEmpty() || command == null || command.metadata() == null) {
            return;
        }
        String threadKey = command.metadata().get(AICommand.THREAD_KEY_FIELD);
        if (threadKey == null) {
            return;
        }
        ConversationThreadRepository threadRepo = conversationThreadRepositoryProvider != null
                ? conversationThreadRepositoryProvider.getIfAvailable() : null;
        if (threadRepo == null) {
            log.debug("RAG: ConversationThreadRepository not available, cannot store documentIds");
            return;
        }

        threadRepo.findByThreadKey(threadKey).ifPresent(thread -> {
            List<String> bullets = thread.getMemoryBullets() != null
                    ? new ArrayList<>(thread.getMemoryBullets()) : new ArrayList<>();
            for (int i = 0; i < documentIds.size(); i++) {
                String docId = documentIds.get(i);
                String filename = i < documentAttachments.size() ? documentAttachments.get(i).filename() : "unknown";
                String bullet = RAG_BULLET_PREFIX + docId + RAG_BULLET_FILENAME_SEPARATOR + filename + "]";
                if (!bullets.contains(bullet)) {
                    bullets.add(bullet);
                }
            }
            thread.setMemoryBullets(bullets);
            threadRepo.save(thread);
            log.info("RAG: stored {} documentId(s) in thread memoryBullets for threadKey={}", documentIds.size(), threadKey);
        });
    }

    /**
     * Extracts RAG documentIds from thread memoryBullets.
     */
    public static List<String> extractRagDocumentIds(List<String> memoryBullets) {
        if (memoryBullets == null) {
            return List.of();
        }
        List<String> docIds = new ArrayList<>();
        for (String bullet : memoryBullets) {
            if (bullet.startsWith(RAG_BULLET_PREFIX)) {
                int endOfDocId = bullet.indexOf(RAG_BULLET_FILENAME_SEPARATOR);
                if (endOfDocId > RAG_BULLET_PREFIX.length()) {
                    docIds.add(bullet.substring(RAG_BULLET_PREFIX.length(), endOfDocId));
                }
            }
        }
        return docIds;
    }

    /**
     * Builds a short placeholder reference for the UserMessage instead of full inline RAG text.
     */
    private String buildRagPlaceholder(List<Attachment> documentAttachments) {
        StringBuilder sb = new StringBuilder("[Documents loaded for context: ");
        for (int i = 0; i < documentAttachments.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(documentAttachments.get(i).filename());
        }
        sb.append("]");
        return sb.toString();
    }

    private void processOneDocumentForRag(Attachment documentAttachment, String userQuery,
            DocumentProcessingService documentProcessingService, FileRAGService fileRagService,
            List<Document> allRelevantChunks, List<Attachment> attachments, List<String> pdfAsImageFilenames,
            List<String> processedDocumentIds) {
        String mimeType = documentAttachment.mimeType() != null ? documentAttachment.mimeType().toLowerCase() : "";
        String documentType = extractDocumentType(mimeType, documentAttachment.filename());
        log.info("processRagIfEnabled: processing document filename={}, mimeType={}, dataLength={}, documentType={}",
                documentAttachment.filename(), mimeType, documentAttachment.data() != null ? documentAttachment.data().length : 0, documentType);
        if (documentType == null) {
            log.warn("Unsupported document type for RAG: {}", mimeType);
            return;
        }
        String documentId;
        if ("pdf".equalsIgnoreCase(documentType)) {
            try {
                documentId = documentProcessingService.processPdf(documentAttachment.data(), documentAttachment.filename());
            } catch (DocumentContentNotExtractableException e) {
                log.info("PDF '{}' has no text layer, rendering pages as images for vision model", documentAttachment.filename());
                List<Attachment> imageAttachments = renderPdfToImageAttachments(documentAttachment.data(), documentAttachment.filename());
                attachments.addAll(imageAttachments);
                pdfAsImageFilenames.add(documentAttachment.filename());
                log.info("Added {} image attachment(s) from PDF '{}' for vision model", imageAttachments.size(), documentAttachment.filename());

                // Vision extraction: use vision model to read text from images, store in RAG.
                // After successful extraction, images are no longer needed — remove them
                // so the final answer uses a TEXT model with RAG context, not VISION.
                String extractedText = null;
                try {
                    extractedText = extractTextFromImagesViaVision(imageAttachments, documentAttachment.filename());
                } catch (Exception ex) {
                    log.warn("Vision text extraction failed for '{}', proceeding with images for direct vision: {}",
                            documentAttachment.filename(), ex.getMessage());
                }
                if (extractedText != null) {
                    // Text extracted — images served their purpose, remove from final message
                    attachments.removeAll(imageAttachments);
                    pdfAsImageFilenames.remove(documentAttachment.filename());
                    log.info("Vision extraction succeeded, removed images from final message for '{}'",
                            documentAttachment.filename());

                    String visionDocId = documentProcessingService.processExtractedText(extractedText, documentAttachment.filename());
                    if (visionDocId != null) {
                        processedDocumentIds.add(visionDocId);
                        List<Document> visionChunks = fileRagService.findAllByDocumentId(visionDocId);
                        allRelevantChunks.addAll(visionChunks);
                        log.info("Vision cache: stored extracted text for '{}', documentId={}, chunks={}",
                                documentAttachment.filename(), visionDocId, visionChunks.size());
                    }
                }
                // If extractedText is null — images stay in attachments as fallback for direct vision
                return;
            }
        } else {
            documentId = documentProcessingService.processWithTika(documentAttachment.data(), documentAttachment.filename(), documentType);
        }
        processedDocumentIds.add(documentId);
        List<Document> relevantChunks = fileRagService.findRelevantContext(userQuery, documentId);
        allRelevantChunks.addAll(relevantChunks);
        log.info("processRagIfEnabled: documentId={}, relevantChunks={}", documentId, relevantChunks.size());
        log.debug("Found {} relevant chunks from '{}'", relevantChunks.size(), documentAttachment.filename());
    }

    /**
     * Checks if RAG feature flag is enabled.
     */
    private boolean isRagEnabled() {
        // RAGProperties is created only when rag.enabled=true (@ConditionalOnProperty)
        // But we check again for safety
        return ragProperties != null;
    }

    /**
     * Maps MIME type and file extension patterns to document types.
     * PDF is checked first as it is processed via PDFBox, not Tika.
     */
    private static final List<DocumentTypeMapping> DOCUMENT_TYPE_MAPPINGS = List.of(
            // PDF - checked first, processed via PDFBox (PagePdfDocumentReader), not Tika
            new DocumentTypeMapping("pdf", List.of("pdf"), List.of(".pdf")),
            // Word documents
            new DocumentTypeMapping("docx", List.of("wordprocessingml"), List.of(".docx")),
            new DocumentTypeMapping("doc", List.of("msword"), List.of(".doc")),
            // Excel documents
            new DocumentTypeMapping("xlsx", List.of("spreadsheetml"), List.of(".xlsx")),
            new DocumentTypeMapping("xls", List.of("ms-excel"), List.of(".xls")),
            // PowerPoint documents
            new DocumentTypeMapping("pptx", List.of("presentationml"), List.of(".pptx")),
            new DocumentTypeMapping("ppt", List.of("ms-powerpoint"), List.of(".ppt")),
            // Text files
            new DocumentTypeMapping("txt", List.of("text/plain"), List.of(".txt")),
            // Rich Text Format
            new DocumentTypeMapping("rtf", List.of("rtf"), List.of(".rtf")),
            // OpenDocument Format (MS Office alternative)
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
            // EPUB (e-books)
            new DocumentTypeMapping("epub", List.of("epub"), List.of(".epub"))
    );

    /**
     * Gets document type from MIME type or filename.
     *
     * <p><b>Note:</b> PDF is detected first and processed via PagePdfDocumentReader (PDFBox), not TikaDocumentReader, for better text extraction.
     *
     * @param mimeType file MIME type
     * @param filename file name
     * @return document type (pdf, docx, doc, xls, xlsx, ppt, pptx, txt, rtf, odt, ods, odp, csv, html, md, json, xml, epub) or null if unsupported
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
     * Helper to map patterns to document types.
     */
    private record DocumentTypeMapping(
            String documentType,
            List<String> mimeTypePatterns,
            List<String> fileExtensions
    ) {
        /**
         * Checks if MIME type or filename matches this mapping.
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
     * Builds system message with attachment context.
     *
     * @param originalImageCount count of original IMAGE attachments (before PDF fallback)
     * @param pdfAsImageFilenames list of PDF filenames converted to images
     * @return system message text or null if no attachments
     */
    private String buildAttachmentContextMessage(int originalImageCount, List<String> pdfAsImageFilenames) {
        boolean hasImages = originalImageCount > 0;
        boolean hasPdfAsImages = !pdfAsImageFilenames.isEmpty();
        if (!hasImages && !hasPdfAsImages) {
            return null;
        }
        StringBuilder context = new StringBuilder();
        appendPdfContext(context, pdfAsImageFilenames);
        appendImagesContext(context, originalImageCount, hasPdfAsImages);
        return context.toString();
    }

    private static void appendPdfContext(StringBuilder context, List<String> pdfAsImageFilenames) {
        if (pdfAsImageFilenames.isEmpty()) return;
        if (pdfAsImageFilenames.size() == 1) {
            context.append("User attached PDF document \"").append(pdfAsImageFilenames.get(0)).append("\" represented as images.");
        } else {
            context.append("User attached PDF documents ");
            for (int i = 0; i < pdfAsImageFilenames.size(); i++) {
                if (i > 0) context.append(", ");
                context.append("\"").append(pdfAsImageFilenames.get(i)).append("\"");
            }
            context.append(", represented as images.");
        }
    }

    private static void appendImagesContext(StringBuilder context, int originalImageCount, boolean hasPdfAsImages) {
        if (originalImageCount <= 0) return;
        if (hasPdfAsImages) context.append("\n");
        if (originalImageCount == 1) {
            context.append("User attached an image.");
        } else {
            context.append("User attached images (").append(originalImageCount).append(").");
        }
    }

    /**
     * Creates UserMessage with multimodal support (images).
     * If no images, returns plain text UserMessage.
     *
     * @param content message text
     * @param attachments list of attachments
     * @return UserMessage (with or without Media)
     */
    private Message createUserMessage(String content, List<Attachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return new UserMessage(content);
        }
        
        // Filter images only
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
     * Converts Attachment to Spring AI Media.
     *
     * @param attachment attachment
     * @return Media for Spring AI
     */
    private Media toMedia(Attachment attachment) {
        var mimeType = MimeTypeUtils.parseMimeType(attachment.mimeType());
        var resource = new ByteArrayResource(attachment.data());
        return new Media(mimeType, resource);
    }

    /**
     * Renders PDF pages to images for vision model.
     *
     * <p>Used as fallback when PDF has no text layer (scan/certificate).
     *
     * @param pdfData PDF bytes
     * @param filename original file name
     * @return list of Attachment with type=IMAGE per page
     */
    List<Attachment> renderPdfToImageAttachments(byte[] pdfData, String filename) {
        try (PDDocument document = Loader.loadPDF(pdfData)) {
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
                BufferedImage image = renderer.renderImageWithDPI(pageIndex, 300);
                
                // Use PNG for OCR/vision extraction to avoid lossy JPEG artifacts on small text.
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(image, "PNG", baos);
                byte[] imageBytes = baos.toByteArray();
                
                String imageFilename = String.format("page_%d_%s.png", pageIndex + 1,
                        filename.replaceAll("\\.pdf$", ""));
                
                Attachment imageAttachment = new Attachment(
                        null,
                        "image/png",
                        imageFilename,
                        imageBytes.length,
                        AttachmentType.IMAGE,
                        imageBytes
                );
                imageAttachments.add(imageAttachment);
            }
            
            log.info("Rendered {} pages from PDF '{}' as images for vision", pagesToRender, filename);
            return imageAttachments;
            
        } catch (Exception e) {
            log.error("Failed to render PDF '{}' pages as images", filename, e);
            return List.of();
        }
    }

    /**
     * Preprocesses a rendered PDF page for more stable OCR on compact local vision models.
     *
     * <p>Pipeline:
     * <ol>
     *   <li>convert to grayscale</li>
     *   <li>auto-contrast (stretch min..max to full 0..255 range)</li>
     * </ol>
     */
    private static BufferedImage preprocessPdfPageForVisionOcr(BufferedImage source) {
        BufferedImage gray = new BufferedImage(
                source.getWidth(), source.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        java.awt.Graphics2D graphics = gray.createGraphics();
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();
        return autoContrastGray(gray);
    }

    private static BufferedImage autoContrastGray(BufferedImage gray) {
        java.awt.image.Raster raster = gray.getRaster();
        int width = gray.getWidth();
        int height = gray.getHeight();
        int min = 255;
        int max = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int value = raster.getSample(x, y, 0);
                if (value < min) min = value;
                if (value > max) max = value;
            }
        }

        if (max <= min) {
            return gray;
        }

        byte[] lut = new byte[256];
        double scale = 255.0d / (max - min);
        for (int i = 0; i < 256; i++) {
            int stretched = (int) Math.round((i - min) * scale);
            if (stretched < 0) stretched = 0;
            if (stretched > 255) stretched = 255;
            lut[i] = (byte) stretched;
        }

        java.awt.image.LookupOp op = new java.awt.image.LookupOp(new java.awt.image.ByteLookupTable(0, lut), null);
        return op.filter(gray, null);
    }

    /**
     * Extracts text content from PDF page images via a vision-capable model.
     *
     * <p>Selects a VISION+CHAT model from registry, sends images with extraction prompt,
     * returns the model's text response containing extracted document content.
     *
     * @param imageAttachments rendered PDF page images
     * @param filename         original PDF filename (for logging)
     * @return extracted text or null if extraction failed or no vision model available
     */
    private static final int VISION_EXTRACTION_MAX_ATTEMPTS = 3;
    private static final int VISION_EXTRACTION_LIKELY_COMPLETE_MIN_CHARS = 600;

    private String extractTextFromImagesViaVision(List<Attachment> imageAttachments, String filename) {
        // Find a vision-capable model
        List<SpringAIModelConfig> visionCandidates = springAIModelRegistry
                .getCandidatesByCapabilities(Set.of(ModelCapabilities.CHAT, ModelCapabilities.VISION), null);
        if (visionCandidates.isEmpty()) {
            log.warn("No VISION-capable model available for text extraction from '{}'", filename);
            return null;
        }
        SpringAIModelConfig visionModel = visionCandidates.getFirst();
        log.info("Using vision model '{}' for text extraction from '{}'", visionModel.getName(), filename);

        String extractionPrompt = ragProperties.getPrompts().getVisionExtractionPrompt();

        List<Media> mediaList = imageAttachments.stream()
                .map(this::toMedia)
                .toList();

        UserMessage userMessage = UserMessage.builder()
                .text(extractionPrompt)
                .media(mediaList)
                .build();

        try {
            String bestExtractedText = null;
            for (int attempt = 1; attempt <= VISION_EXTRACTION_MAX_ATTEMPTS; attempt++) {
                String extractedText = chatService.callSimpleVision(visionModel, List.of(userMessage));
                if (extractedText == null || extractedText.isBlank()) {
                    log.warn("Vision extraction attempt {}/{} returned empty text for '{}'",
                            attempt, VISION_EXTRACTION_MAX_ATTEMPTS, filename);
                    continue;
                }

                extractedText = stripModelInternalTokens(extractedText);
                log.info("Vision extraction attempt {}/{} for '{}': {} chars",
                        attempt, VISION_EXTRACTION_MAX_ATTEMPTS, filename, extractedText.length());

                if (!extractedText.isBlank()
                        && (bestExtractedText == null || extractedText.length() > bestExtractedText.length())) {
                    bestExtractedText = extractedText;
                }

                if (isLikelyCompleteVisionExtraction(bestExtractedText)) {
                    break;
                }
            }

            if (bestExtractedText != null && !bestExtractedText.isBlank()) {
                log.info("Vision extraction succeeded for '{}': {} chars", filename, bestExtractedText.length());
                log.debug("Vision extracted text for '{}': [{}]", filename, bestExtractedText);
                return bestExtractedText;
            }

            log.warn("Vision extraction returned empty text for '{}'", filename);
            return null;
        } catch (Exception e) {
            log.error("Vision extraction failed for '{}': {}", filename, e.getMessage());
            return null;
        }
    }

    /**
     * Strips model-internal tokens (e.g. {@code <start_of_image>}, {@code <end_of_turn>})
     * that some vision models (gemma3, llava) leak into their text output.
     */
    public static String stripModelInternalTokens(String text) {
        if (text == null) return null;
        return text.replaceAll("<start_of_image>|<end_of_image>|<end_of_turn>|<start_of_turn>", "")
                .strip();
    }

    private static boolean isLikelyCompleteVisionExtraction(String text) {
        return text != null && text.length() >= VISION_EXTRACTION_LIKELY_COMPLETE_MIN_CHARS;
    }

    private static int countMatchingCaps(Set<ModelCapabilities> modelCaps, Set<ModelCapabilities> optional) {
        if (modelCaps == null || optional == null) return 0;
        int count = 0;
        for (ModelCapabilities cap : optional) {
            if (modelCaps.contains(cap)) count++;
        }
        return count;
    }

    private static boolean hasUserMedia(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        return messages.stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .anyMatch(message -> message.getMedia() != null && !message.getMedia().isEmpty());
    }

    /**
     * For text-only payloads in AUTO mode, prefer non-VISION candidates when available.
     *
     * <p>This avoids routing plain follow-up questions to compact multimodal models when
     * dedicated text models are configured in the same pool.
     */
    private List<SpringAIModelConfig> preferTextOnlyModelsForTextPayload(
            List<SpringAIModelConfig> candidates,
            boolean requiresVisionForPayload
    ) {
        if (requiresVisionForPayload || candidates == null || candidates.isEmpty()) {
            return candidates;
        }
        List<SpringAIModelConfig> textOnlyCandidates = candidates.stream()
                .filter(model -> model.getCapabilities() == null
                        || !model.getCapabilities().contains(ModelCapabilities.VISION))
                .toList();
        if (textOnlyCandidates.isEmpty()) {
            return candidates;
        }
        if (textOnlyCandidates.size() != candidates.size()) {
            log.info("AUTO selection: text-only payload, preferring non-VISION models ({} of {} candidates)",
                    textOnlyCandidates.size(), candidates.size());
        }
        return textOnlyCandidates;
    }

}
