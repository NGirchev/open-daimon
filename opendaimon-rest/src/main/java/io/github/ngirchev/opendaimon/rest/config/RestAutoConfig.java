package io.github.ngirchev.opendaimon.rest.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import io.github.ngirchev.opendaimon.bulkhead.service.IUserPriorityService;
import io.github.ngirchev.opendaimon.common.ai.factory.AICommandFactoryRegistry;
import io.github.ngirchev.opendaimon.common.config.CoreCommonProperties;
import io.github.ngirchev.opendaimon.common.repository.OpenDaimonMessageRepository;
import io.github.ngirchev.opendaimon.common.repository.ConversationThreadRepository;
import io.github.ngirchev.opendaimon.common.service.*;
import io.github.ngirchev.opendaimon.rest.controller.SessionController;
import io.github.ngirchev.opendaimon.rest.handler.RestChatHandlerSupport;
import io.github.ngirchev.opendaimon.rest.handler.RestChatMessageCommandHandler;
import io.github.ngirchev.opendaimon.rest.handler.RestChatStreamMessageCommandHandler;
import io.github.ngirchev.opendaimon.rest.repository.RestUserRepository;
import io.github.ngirchev.opendaimon.rest.service.ChatService;
import io.github.ngirchev.opendaimon.rest.service.RestAuthorizationService;
import io.github.ngirchev.opendaimon.rest.service.RestMessageService;
import io.github.ngirchev.opendaimon.rest.service.RestUserService;
import io.github.ngirchev.opendaimon.rest.service.RestUsersStartupInitializer;
import io.github.ngirchev.opendaimon.rest.exception.RestExceptionHandler;

/**
 * Auto-configuration for REST module.
 * Creates beans required for REST API.
 * Active only when REST module is enabled (open-daimon.rest.enabled=true).
 */
@AutoConfiguration
@EnableConfigurationProperties(RestProperties.class)
@Import({
        RestJpaConfig.class,
        RestFlywayConfig.class
})
@ConditionalOnProperty(name = "open-daimon.rest.enabled", havingValue = "true")
public class RestAutoConfig {

    @Bean
    @ConditionalOnMissingBean
    public RestUsersStartupInitializer restUsersStartupInitializer(
            RestUserService restUserService,
            RestProperties restProperties) {
        return new RestUsersStartupInitializer(restUserService, restProperties);
    }

    @Bean
    @ConditionalOnMissingBean
    public RestUserService restUserService(
            RestUserRepository restUserRepository,
            AssistantRoleService assistantRoleService,
            RestProperties restProperties) {
        return new RestUserService(restUserRepository, assistantRoleService, restProperties);
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
            OpenDaimonMessageService messageService,
            RestUserService restUserService,
            CoreCommonProperties coreCommonProperties) {
        return new RestMessageService(
                messageService,
                restUserService,
                coreCommonProperties);
    }

    @Bean
    @ConditionalOnMissingBean
    public RestChatHandlerSupport restChatHandlerSupport(
            ObjectMapper objectMapper,
            MessageLocalizationService messageLocalizationService,
            OpenDaimonMessageService messageService) {
        return new RestChatHandlerSupport(objectMapper, messageLocalizationService, messageService);
    }

    @Bean
    @ConditionalOnMissingBean
    public RestChatMessageCommandHandler restChatMessageCommandHandler(
            RestMessageService restMessageService,
            RestUserService restUserService,
            OpenDaimonMessageService messageService,
            AIGatewayRegistry aiGatewayRegistry,
            AICommandFactoryRegistry aiCommandFactoryRegistry,
            RestChatHandlerSupport restChatHandlerSupport) {
        return new RestChatMessageCommandHandler(
                restMessageService,
                restUserService,
                messageService,
                aiGatewayRegistry,
                aiCommandFactoryRegistry,
                restChatHandlerSupport
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public RestChatStreamMessageCommandHandler restChatStreamMessageCommandHandler(
            RestMessageService restMessageService,
            RestUserService restUserService,
            OpenDaimonMessageService messageService,
            AIGatewayRegistry aiGatewayRegistry,
            AICommandFactoryRegistry aiCommandFactoryRegistry,
            RestChatHandlerSupport restChatHandlerSupport) {
        return new RestChatStreamMessageCommandHandler(
                restMessageService,
                restUserService,
                messageService,
                aiGatewayRegistry,
                aiCommandFactoryRegistry,
                restChatHandlerSupport
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatService restChatService(
            ConversationThreadRepository conversationThreadRepository,
            ConversationThreadService conversationThreadService,
            OpenDaimonMessageRepository messageRepository,
            CommandSyncService commandSyncService,
            IUserPriorityService userPriorityService) {
        return new ChatService(
                conversationThreadRepository,
                conversationThreadService,
                messageRepository,
                commandSyncService,
                userPriorityService);
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

