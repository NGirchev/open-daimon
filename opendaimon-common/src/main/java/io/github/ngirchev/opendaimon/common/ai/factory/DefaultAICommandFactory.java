package io.github.ngirchev.opendaimon.common.ai.factory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.bulkhead.service.IUserPriorityService;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.ai.ModelDescriptionCache;
import io.github.ngirchev.opendaimon.common.exception.UnsupportedModelCapabilityException;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.command.ChatAICommand;
import io.github.ngirchev.opendaimon.common.ai.command.FixedModelChatAICommand;
import org.springframework.util.StringUtils;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.command.IChatCommand;
import io.github.ngirchev.opendaimon.common.config.CoreCommonProperties;
import io.github.ngirchev.opendaimon.common.model.Attachment;
import io.github.ngirchev.opendaimon.common.model.AttachmentType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.github.ngirchev.opendaimon.common.ai.LlmParamNames.MAX_PRICE;
import static io.github.ngirchev.opendaimon.common.ai.command.AICommand.LANGUAGE_CODE_FIELD;
import static io.github.ngirchev.opendaimon.common.ai.command.AICommand.PREFERRED_MODEL_ID_FIELD;
import static io.github.ngirchev.opendaimon.common.ai.command.AICommand.ROLE_FIELD;
@Slf4j
public class DefaultAICommandFactory implements AICommandFactory<AICommand, ICommand<?>> {

    private final IUserPriorityService userPriorityService;
    private final ModelDescriptionCache modelDescriptionCache;
    private final CoreCommonProperties coreCommonProperties;

    public DefaultAICommandFactory(
            IUserPriorityService userPriorityService,
            CoreCommonProperties coreCommonProperties) {
        this(userPriorityService, null, coreCommonProperties);
    }

    public DefaultAICommandFactory(
            IUserPriorityService userPriorityService,
            ModelDescriptionCache modelDescriptionCache,
            CoreCommonProperties coreCommonProperties) {
        this.userPriorityService = userPriorityService;
        this.modelDescriptionCache = modelDescriptionCache;
        this.coreCommonProperties = coreCommonProperties;
    }

    @Override
    public int priority() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public boolean supports(ICommand<?> input, Map<String, String> metadata) {
        return true;
    }

    @Override
    public AICommand createCommand(ICommand<?> command, Map<String, String> metadata) {
        if (command instanceof IChatCommand<?> chatCommand) {
            if (chatCommand.userText() == null) {
                throw new IllegalStateException("User text is required for message command");
            }
            metadata = new HashMap<>(metadata != null ? metadata : Map.of());
            List<Attachment> attachments = chatCommand.attachments() != null
                    ? chatCommand.attachments()
                    : List.of();
            String attachmentTypes = attachments.stream().map(a -> a.type().toString()).toList().toString();
            UserPriority priority = Optional.ofNullable(userPriorityService.getUserPriority(command.userId()))
                    .orElse(UserPriority.REGULAR);
            log.info("Creating ChatAICommand: userText='{}', attachmentsCount={}, attachmentTypes={}, priority={}",
                    chatCommand.userText(), attachments.size(), attachmentTypes, priority);
            metadata.put(AICommand.USER_PRIORITY_FIELD, priority.name());
            Map<String, Object> body = new HashMap<>();

            CoreCommonProperties.PriorityChatRoutingProperties tier =
                    switch (priority) {
                        case ADMIN -> coreCommonProperties.getChatRouting().getAdmin();
                        case VIP -> coreCommonProperties.getChatRouting().getVip();
                        default -> coreCommonProperties.getChatRouting().getRegular();
                    };
            if (tier.getMaxPrice() != null) {
                body.put(MAX_PRICE, tier.getMaxPrice());
            }
            Set<ModelCapabilities> optionalModelCapabilities = Set.copyOf(tier.getOptionalCapabilities());
            Set<ModelCapabilities> baseModelCapabilities = Set.copyOf(tier.getRequiredCapabilities());

            // Add VISION dynamically if there are images
            Set<ModelCapabilities> modelCapabilities = addVisionIfNeeded(baseModelCapabilities, attachments);

            String fixedModelId = metadata.get(PREFERRED_MODEL_ID_FIELD);
            String routingModelLabel = StringUtils.hasText(fixedModelId) ? fixedModelId : "(auto)";
            log.info(
                    "Chat routing: priority={}, preferredModelId={}, maxPrice={}, requiredCapabilities={}, optionalCapabilities={}",
                    priority,
                    routingModelLabel,
                    body.get(MAX_PRICE),
                    modelCapabilities,
                    optionalModelCapabilities);

            // Temperature 0.35 for general assistant (recommended range: 0.3-0.4)
            String systemRole = metadata.get(ROLE_FIELD);
            if (StringUtils.hasText(fixedModelId)) {
                Set<ModelCapabilities> fixedModelCapabilities;
                if (modelDescriptionCache != null) {
                    fixedModelCapabilities = modelDescriptionCache.getCapabilities(fixedModelId);
                    // For explicitly selected models only validate VISION — routing capabilities
                    // (TOOL_CALLING, WEB) are used for auto-selection only and must
                    // not be enforced against a model the user has deliberately chosen.
                    boolean needsVision = modelCapabilities.contains(ModelCapabilities.VISION);
                    if (needsVision && !fixedModelCapabilities.contains(ModelCapabilities.VISION)) {
                        throw new UnsupportedModelCapabilityException(fixedModelId, Set.of(ModelCapabilities.VISION));
                    }
                } else {
                    fixedModelCapabilities = Set.of();
                }
                return new FixedModelChatAICommand(
                        fixedModelId,
                        fixedModelCapabilities,
                        0.35,
                        coreCommonProperties.getMaxOutputTokens(),
                        coreCommonProperties.getMaxReasoningTokens(),
                        systemRole,
                        chatCommand.userText(),
                        chatCommand.stream(),
                        metadata,
                        body,
                        attachments
                );
            } else {
                return new ChatAICommand(
                        modelCapabilities,
                        optionalModelCapabilities,
                        0.35,
                        coreCommonProperties.getMaxOutputTokens(),
                        coreCommonProperties.getMaxReasoningTokens(),
                        systemRole,
                        chatCommand.userText(),
                        chatCommand.stream(),
                        metadata,
                        body,
                        attachments
                );
            }
        } else {
            throw new IllegalArgumentException("Supported type is IChatCommand");
        }
    }

    /**
     * Adds ModelType.VISION if there are image attachments.
     */
    private Set<ModelCapabilities> addVisionIfNeeded(Set<ModelCapabilities> baseTypes, List<Attachment> attachments) {
        boolean hasImages = attachments.stream()
                .anyMatch(a -> a.type() == AttachmentType.IMAGE);
        
        if (hasImages) {
            Set<ModelCapabilities> withVision = new HashSet<>(baseTypes);
            withVision.add(ModelCapabilities.VISION);
            return withVision;
        }
        return baseTypes;
    }
}
