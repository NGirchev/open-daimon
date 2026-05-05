package io.github.ngirchev.opendaimon.telegram.command.handler.impl;

import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.bulkhead.service.IUserPriorityService;
import io.github.ngirchev.opendaimon.common.ai.tool.ExternalToolAccessContext;
import io.github.ngirchev.opendaimon.common.ai.tool.ExternalToolCatalogService;
import io.github.ngirchev.opendaimon.common.ai.tool.ExternalToolDescriptor;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommandType;
import io.github.ngirchev.opendaimon.telegram.command.handler.AbstractTelegramCommandHandlerWithResponseSend;
import io.github.ngirchev.opendaimon.telegram.service.TelegramHtmlEscaper;
import io.github.ngirchev.opendaimon.telegram.service.TypingIndicatorService;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Comparator;
import java.util.List;

public class McpTelegramCommandHandler extends AbstractTelegramCommandHandlerWithResponseSend {

    private final ObjectProvider<ExternalToolCatalogService> externalToolCatalogServiceProvider;
    private final IUserPriorityService userPriorityService;

    public McpTelegramCommandHandler(
            ObjectProvider<TelegramBot> telegramBotProvider,
            TypingIndicatorService typingIndicatorService,
            MessageLocalizationService messageLocalizationService,
            ObjectProvider<ExternalToolCatalogService> externalToolCatalogServiceProvider,
            IUserPriorityService userPriorityService) {
        super(telegramBotProvider, typingIndicatorService, messageLocalizationService);
        this.externalToolCatalogServiceProvider = externalToolCatalogServiceProvider;
        this.userPriorityService = userPriorityService;
    }

    @Override
    protected boolean shouldShowTypingIndicator(TelegramCommand command) {
        return false;
    }

    @Override
    public boolean canHandle(ICommand<TelegramCommandType> command) {
        var commandType = command.commandType();
        return command instanceof TelegramCommand
                && commandType != null
                && TelegramCommand.MCP.equals(commandType.command());
    }

    @Override
    public String handleInner(TelegramCommand command) {
        ExternalToolCatalogService catalog = externalToolCatalogServiceProvider.getIfAvailable();
        if (catalog == null) {
            return messageLocalizationService.getMessage("telegram.mcp.unavailable", command.languageCode());
        }

        UserPriority priority = userPriorityService.getUserPriority(command.userId());
        List<ExternalToolDescriptor> tools = catalog.listAvailableTools(
                new ExternalToolAccessContext(command.userId(), priority));
        if (tools.isEmpty()) {
            return messageLocalizationService.getMessage("telegram.mcp.empty", command.languageCode(), priority);
        }

        StringBuilder response = new StringBuilder(messageLocalizationService.getMessage(
                "telegram.mcp.header", command.languageCode(), priority));
        tools.stream()
                .sorted(Comparator.comparing(ExternalToolDescriptor::name))
                .forEach(tool -> response.append('\n').append(renderTool(tool)));
        return response.toString();
    }

    private static String renderTool(ExternalToolDescriptor tool) {
        String name = TelegramHtmlEscaper.escape(tool.name());
        if (tool.description() == null || tool.description().isBlank()) {
            return "• <code>" + name + "</code>";
        }
        return "• <code>" + name + "</code> — " + TelegramHtmlEscaper.escape(tool.description());
    }

    @Override
    public String getSupportedCommandText(String languageCode) {
        return messageLocalizationService.getMessage("telegram.command.mcp.desc", languageCode);
    }
}
