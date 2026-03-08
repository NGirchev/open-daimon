package io.github.ngirchev.aibot.common.ai.factory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import io.github.ngirchev.aibot.bulkhead.model.UserPriority;
import io.github.ngirchev.aibot.bulkhead.service.IUserPriorityService;
import io.github.ngirchev.aibot.common.ai.ModelCapabilities;
import io.github.ngirchev.aibot.common.ai.command.AICommand;
import io.github.ngirchev.aibot.common.ai.command.ChatAICommand;
import io.github.ngirchev.aibot.common.command.ICommand;
import io.github.ngirchev.aibot.common.command.IChatCommand;
import io.github.ngirchev.aibot.common.model.Attachment;
import io.github.ngirchev.aibot.common.model.AttachmentType;

import java.util.*;

import static io.github.ngirchev.aibot.common.ai.LlmParamNames.MAX_PRICE;
import static io.github.ngirchev.aibot.common.ai.command.AICommand.ROLE_FIELD;
import static io.github.ngirchev.aibot.common.ai.ModelCapabilities.*;

@Slf4j
@RequiredArgsConstructor
public class DefaultAICommandFactory implements AICommandFactory<AICommand, ICommand<?>> {

    private final IUserPriorityService userPriorityService;
    private final int maxOutputTokens;
    private final Integer maxReasoningTokens;

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
            List<Attachment> attachments = chatCommand.attachments() != null 
                    ? chatCommand.attachments() 
                    : List.of();
            String attachmentTypes = attachments.stream().map(a -> a.type().toString()).toList().toString();
            log.info("Creating ChatAICommand: userText='{}', attachmentsCount={}, attachmentTypes={}",
                    chatCommand.userText(), attachments.size(), attachmentTypes);
            UserPriority priority = Optional.ofNullable(userPriorityService.getUserPriority(command.userId()))
                    .orElse(UserPriority.REGULAR);
            log.info("User {} resolved as ADMIN", command.userId());
            Map<String, Object> body = new HashMap<>();
            
            // Base modelTypes depending on priority
            Set<ModelCapabilities> baseModelCapabilities = switch (priority) {
                case ADMIN -> Set.of(AUTO);
                case VIP -> {
                    body.put(MAX_PRICE, 0);
                    yield Set.of(CHAT, MODERATION, TOOL_CALLING, WEB);
                }
                default -> Set.of(CHAT);
            };
            
            // Add VISION dynamically if there are images
            Set<ModelCapabilities> modelCapabilities = addVisionIfNeeded(baseModelCapabilities, attachments);

            // Temperature 0.35 for general assistant (recommended range: 0.3-0.4)
            return new ChatAICommand(
                    modelCapabilities,
                    0.35,
                    maxOutputTokens,
                    maxReasoningTokens,
                    metadata.get(ROLE_FIELD),
                    chatCommand.userText(),
                    chatCommand.stream(),
                    metadata,
                    body,
                    attachments
            );
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
