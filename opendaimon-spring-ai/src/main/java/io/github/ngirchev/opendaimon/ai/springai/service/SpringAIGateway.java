package io.github.ngirchev.opendaimon.ai.springai.service;

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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.UrlResource;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIModelConfig;
import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIProperties;
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
import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.common.service.AIGateway;
import io.github.ngirchev.opendaimon.common.service.AIGatewayRegistry;

import java.net.MalformedURLException;
import java.util.*;
import java.util.Base64;
import java.util.stream.Collectors;

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
    
    public SpringAIGateway(
            SpringAIProperties springAiProperties,
            AIGatewayRegistry aiGatewayRegistry,
            SpringAIModelRegistry springAIModelRegistry,
            SpringAIChatService chatService,
            ObjectProvider<ChatMemory> chatMemoryProvider) {
        this.springAiProperties = springAiProperties;
        this.aiGatewayRegistry = aiGatewayRegistry;
        this.springAIModelRegistry = springAIModelRegistry;
        this.chatService = chatService;
        this.chatMemoryProvider = chatMemoryProvider;
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
                if (log.isDebugEnabled()) {
                    for (int i = 0; i < messages.size(); i++) {
                        Message msg = messages.get(i);
                        if (msg instanceof UserMessage um) {
                            boolean hasMedia = um.getMedia() != null && !um.getMedia().isEmpty();
                            log.debug("Gateway: message[{}] UserMessage hasMedia={}, mediaCount={}, textLength={}",
                                    i, hasMedia, hasMedia ? um.getMedia().size() : 0,
                                    um.getText() != null ? um.getText().length() : 0);
                        }
                    }
                }
                return executeChatWithOptions(chatOptions, command, messages);
            } else {
                throw new IllegalArgumentException();
            }
        } catch (WebClientResponseException e) {
            log.error(LOG_ERROR_CALLING_SPRING_AI, e.getMessage());
            throw new RuntimeException("Failed to generate response from Spring AI", e);
        } catch (UnsupportedModelCapabilityException | DocumentContentNotExtractableException e) {
            throw e;
        }  catch (Exception e) {
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
                    .collect(Collectors.toSet()));
            if (requiresVisionForPayload) {
                requiredCapabilities.add(ModelCapabilities.VISION);
            }
            if (!requiredCapabilities.isEmpty() && !liveCapabilities.containsAll(requiredCapabilities)) {
                Set<ModelCapabilities> missing = requiredCapabilities.stream()
                        .filter(c -> !liveCapabilities.contains(c))
                        .collect(Collectors.toSet());
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
                    .collect(Collectors.toSet());
            if (!effectiveRequired.isEmpty() && !liveCapabilities.containsAll(effectiveRequired)) {
                Set<ModelCapabilities> missing = effectiveRequired.stream()
                        .filter(c -> !liveCapabilities.contains(c))
                        .collect(Collectors.toSet());
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
        switch (imageUrlObj) {
            case Map<?, ?> urlMap -> {
                url = (String) urlMap.get(IMAGE_URL_URL);
            }
            case String s -> {
                url = s;
            }
            default -> {
                return null;
            }
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
        assert attachments != null;
        long originalImageCount = attachments.stream().filter(att -> att.type() == AttachmentType.IMAGE).count();

        // Document orchestration already happened in AIRequestPipeline (before factory).
        // userRole contains the augmented query, attachments contain modified list (with images from PDF if OCR failed).
        // pdfAsImageFilenames come from pipeline metadata.
        List<String> pdfAsImageFilenames = List.of();
        if (command.metadata() != null && command.metadata().containsKey("pdfAsImageFilenames")) {
            pdfAsImageFilenames = List.of(command.metadata().get("pdfAsImageFilenames").split(","));
        }

        addAttachmentContextToMessagesAndMemory(messages, (int) originalImageCount, pdfAsImageFilenames, command);
        messages.add(createUserMessage(userRole, new ArrayList<>(attachments)));
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
        return systemRole + "\nPrefer responding in " + languageName + " (" + languageCode + "). When quoting text from documents or context, preserve the original language exactly.";
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
