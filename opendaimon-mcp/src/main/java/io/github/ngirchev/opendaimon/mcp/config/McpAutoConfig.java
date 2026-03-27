package io.github.ngirchev.opendaimon.mcp.config;

import io.github.ngirchev.opendaimon.bulkhead.service.IUserPriorityService;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.mcp.handler.impl.McpCommandHandler;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.service.TypingIndicatorService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "open-daimon.mcp.enabled", havingValue = "true", matchIfMissing = true)
public class McpAutoConfig {

    @Bean
    @ConditionalOnMissingBean
    public McpCommandHandler mcpCommandHandler(
            ObjectProvider<TelegramBot> telegramBotProvider,
            TypingIndicatorService typingIndicatorService,
            MessageLocalizationService messageLocalizationService,
            IUserPriorityService userPriorityService,
            ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider) {
        return new McpCommandHandler(
                telegramBotProvider,
                typingIndicatorService,
                messageLocalizationService,
                userPriorityService,
                mcpToolCallbackProvider);
    }
}
