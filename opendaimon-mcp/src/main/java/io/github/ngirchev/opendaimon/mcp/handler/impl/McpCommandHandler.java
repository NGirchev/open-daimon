package io.github.ngirchev.opendaimon.mcp.handler.impl;

import io.github.ngirchev.opendaimon.bulkhead.service.IUserPriorityService;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.mcp.handler.AbstractAdminTelegramCommandHandler;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommandType;
import io.github.ngirchev.opendaimon.telegram.command.handler.TelegramCommandHandlerException;
import io.github.ngirchev.opendaimon.telegram.service.TypingIndicatorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Arrays;
import java.util.stream.Collectors;

@Slf4j
public class McpCommandHandler extends AbstractAdminTelegramCommandHandler {

    private final ObjectProvider<ToolCallbackProvider> mcpProvider;

    public McpCommandHandler(
            ObjectProvider<TelegramBot> telegramBotProvider,
            TypingIndicatorService typingIndicatorService,
            MessageLocalizationService messageLocalizationService,
            IUserPriorityService userPriorityService,
            ObjectProvider<ToolCallbackProvider> mcpProvider) {
        super(telegramBotProvider, typingIndicatorService, messageLocalizationService, userPriorityService);
        this.mcpProvider = mcpProvider;
    }

    @Override
    public boolean canHandle(ICommand<TelegramCommandType> command) {
        if (!(command instanceof TelegramCommand telegramCommand)) {
            return false;
        }
        var commandType = telegramCommand.commandType();
        return commandType != null && TelegramCommand.MCP.equals(commandType.command());
    }

    @Override
    protected String handleAdminInner(TelegramCommand command) throws TelegramCommandHandlerException {
        ToolCallbackProvider provider = mcpProvider.getIfAvailable();
        if (provider == null) {
            return "MCP: no servers configured";
        }
        ToolCallback[] callbacks = provider.getToolCallbacks();
        if (callbacks == null || callbacks.length == 0) {
            return "MCP: connected but no tools available";
        }
        String toolList = Arrays.stream(callbacks)
                .map(cb -> "• " + cb.getToolDefinition().name() + " — " + cb.getToolDefinition().description())
                .collect(Collectors.joining("\n"));
        return "MCP tools (" + callbacks.length + "):\n\n" + toolList;
    }

    @Override
    public int priority() {
        return 10;
    }
}
