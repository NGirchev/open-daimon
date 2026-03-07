package ru.girchev.aibot.rest.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import ru.girchev.aibot.common.ai.factory.AICommandFactoryRegistry;
import ru.girchev.aibot.common.config.CoreCommonProperties;
import ru.girchev.aibot.common.repository.AIBotMessageRepository;
import ru.girchev.aibot.common.repository.ConversationThreadRepository;
import ru.girchev.aibot.common.service.*;
import ru.girchev.aibot.rest.controller.SessionController;
import ru.girchev.aibot.rest.handler.RestChatMessageCommandHandler;
import ru.girchev.aibot.rest.handler.RestChatStreamMessageCommandHandler;
import ru.girchev.aibot.rest.repository.RestUserRepository;
import ru.girchev.aibot.rest.service.ChatService;
import ru.girchev.aibot.rest.service.RestAuthorizationService;
import ru.girchev.aibot.rest.service.RestMessageService;
import ru.girchev.aibot.rest.service.RestUserService;
import ru.girchev.aibot.rest.exception.RestExceptionHandler;

/**
 * Auto-configuration for REST module.
 * Creates beans required for REST API.
 * Active only when REST module is enabled (ai-bot.rest.enabled=true).
 */
@AutoConfiguration
@Import({
        RestJpaConfig.class,
        RestFlywayConfig.class
})
@ConditionalOnProperty(name = "ai-bot.rest.enabled", havingValue = "true")
public class RestAutoConfig {

    @Bean
    @ConditionalOnMissingBean
    public RestUserService restUserService(
            RestUserRepository restUserRepository,
            AssistantRoleService assistantRoleService) {
        return new RestUserService(restUserRepository, assistantRoleService);
    }

    @Bean
    @ConditionalOnMissingBean
    public RestAuthorizationService restAuthorizationService(
            RestUserRepository restUserRepository,
            MessageLocalizationService messageLocalizationService) {
        return new RestAuthorizationService(restUserRepository, messageLocalizationService);
    }

    @Bean
    @ConditionalOnMissingBean
    public RestMessageService restMessageService(
            AIBotMessageService messageService,
            RestUserService restUserService,
            CoreCommonProperties coreCommonProperties) {
        return new RestMessageService(
                messageService,
                restUserService,
                coreCommonProperties);
    }

    @Bean
    @ConditionalOnMissingBean
    public RestChatMessageCommandHandler restChatMessageCommandHandler(
            RestMessageService restMessageService,
            RestUserService restUserService,
            AIBotMessageService messageService,
            AIGatewayRegistry aiGatewayRegistry,
            AICommandFactoryRegistry aiCommandFactoryRegistry,
            ObjectMapper objectMapper,
            MessageLocalizationService messageLocalizationService) {
        return new RestChatMessageCommandHandler(
                restMessageService,
                restUserService,
                messageService,
                aiGatewayRegistry,
                aiCommandFactoryRegistry,
                objectMapper,
                messageLocalizationService
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public RestChatStreamMessageCommandHandler restChatStreamMessageCommandHandler(
            RestMessageService restMessageService,
            RestUserService restUserService,
            AIBotMessageService messageService,
            AIGatewayRegistry aiGatewayRegistry,
            AICommandFactoryRegistry aiCommandFactoryRegistry,
            ObjectMapper objectMapper,
            MessageLocalizationService messageLocalizationService) {
        return new RestChatStreamMessageCommandHandler(
                restMessageService,
                restUserService,
                messageService,
                aiGatewayRegistry,
                aiCommandFactoryRegistry,
                objectMapper,
                messageLocalizationService
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatService restChatService(
            ConversationThreadRepository conversationThreadRepository,
            ConversationThreadService conversationThreadService,
            AIBotMessageRepository messageRepository,
            CommandSyncService commandSyncService) {
        return new ChatService(
                conversationThreadRepository,
                conversationThreadService,
                messageRepository,
                commandSyncService);
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionController sessionController(
            ChatService restChatService,
            RestAuthorizationService restAuthorizationService,
            MessageLocalizationService messageLocalizationService) {
        return new SessionController(restChatService, restAuthorizationService, messageLocalizationService);
    }

    @Bean
    @ConditionalOnMissingBean
    public RestExceptionHandler restExceptionHandler(MessageLocalizationService messageLocalizationService) {
        return new RestExceptionHandler(messageLocalizationService);
    }
}

