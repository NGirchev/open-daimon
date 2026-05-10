package io.github.ngirchev.opendaimon.telegram.command.handler.impl;

import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.bulkhead.service.IUserPriorityService;
import io.github.ngirchev.opendaimon.common.ai.tool.ExternalToolAccessContext;
import io.github.ngirchev.opendaimon.common.ai.tool.ExternalToolCatalogService;
import io.github.ngirchev.opendaimon.common.ai.tool.ExternalToolSourceDescriptor;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommandType;
import io.github.ngirchev.opendaimon.telegram.command.handler.AbstractTelegramCommandHandlerWithResponseSend;
import io.github.ngirchev.opendaimon.telegram.service.TelegramHtmlEscaper;
import io.github.ngirchev.opendaimon.telegram.service.TypingIndicatorService;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.stream.Collectors;

public class McpTelegramCommandHandler extends AbstractTelegramCommandHandlerWithResponseSend {

    private static final int MAX_RESPONSE_CHARS = 3900;
    private static final int MAX_TOOL_LINE_CHARS = 1000;

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
        List<ExternalToolSourceDescriptor> sources = catalog.listAvailableToolSources(
                new ExternalToolAccessContext(command.userId(), priority));
        if (sources.isEmpty()) {
            return messageLocalizationService.getMessage("telegram.mcp.empty", command.languageCode(), priority);
        }

        StringBuilder response = new StringBuilder(messageLocalizationService.getMessage(
                "telegram.mcp.header", command.languageCode(), priority));
        String line = "\n" + renderToolLine(sources);
        if (response.length() + line.length() > MAX_RESPONSE_CHARS) {
            response.append("\n...");
        } else {
            response.append(line);
        }
        return response.toString();
    }

    private static String renderToolLine(List<ExternalToolSourceDescriptor> sources) {
        String commands = sources.stream()
                .sorted(java.util.Comparator.comparing(ExternalToolSourceDescriptor::name))
                .map(McpTelegramCommandHandler::renderSourceTools)
                .collect(Collectors.joining("\n"));
        return TelegramHtmlEscaper.escape(truncateToolLine(commands));
    }

    private static String renderSourceTools(ExternalToolSourceDescriptor source) {
        String commands = source.tools().stream()
                .map(tool -> tool.name())
                .sorted()
                .collect(Collectors.joining(", "));
        return "• " + source.name() + " - " + commands;
    }

    private static String truncateToolLine(String commands) {
        if (commands.length() <= MAX_TOOL_LINE_CHARS) {
            return commands;
        }
        return commands.substring(0, MAX_TOOL_LINE_CHARS - 3) + "...";
    }

    @Override
    public String getSupportedCommandText(String languageCode) {
        return messageLocalizationService.getMessage("telegram.command.mcp.desc", languageCode);
    }
}
