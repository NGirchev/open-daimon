package io.github.ngirchev.aibot.telegram.command.handler.impl;

import org.springframework.beans.factory.ObjectProvider;
import io.github.ngirchev.aibot.common.command.ICommand;
import io.github.ngirchev.aibot.telegram.TelegramBot;
import io.github.ngirchev.aibot.telegram.command.TelegramCommand;
import io.github.ngirchev.aibot.telegram.command.TelegramCommandType;
import io.github.ngirchev.aibot.common.service.MessageLocalizationService;
import io.github.ngirchev.aibot.telegram.command.handler.AbstractTelegramCommandHandlerWithResponseSend;
import io.github.ngirchev.aibot.telegram.command.handler.TelegramSupportedCommandProvider;
import io.github.ngirchev.aibot.telegram.service.TypingIndicatorService;

import java.util.Objects;
import java.util.stream.Collectors;

public class StartTelegramCommandHandler extends AbstractTelegramCommandHandlerWithResponseSend {

    private final ObjectProvider<TelegramSupportedCommandProvider> handlersProvider;

    public StartTelegramCommandHandler(ObjectProvider<TelegramBot> telegramBotProvider,
                                       TypingIndicatorService typingIndicatorService,
                                       MessageLocalizationService messageLocalizationService,
                                       ObjectProvider<TelegramSupportedCommandProvider> handlersProvider) {
        super(telegramBotProvider, typingIndicatorService, messageLocalizationService);
        this.handlersProvider = handlersProvider;
    }

    @Override
    public boolean canHandle(ICommand<TelegramCommandType> command) {
        if (!(command instanceof TelegramCommand telegramCommand)) {
            return false;
        }
        var commandType = command.commandType();
        return commandType != null
                && commandType.command() != null
                && commandType.command().equals(TelegramCommand.START)
                && !telegramCommand.update().hasCallbackQuery();
    }

    @Override
    public String handleInner(TelegramCommand command) {
        return messageLocalizationService.getMessage("telegram.start.message", command.languageCode())
                + handlersProvider.orderedStream()
                .filter(h -> h != this)
                .map(h -> h.getSupportedCommandText(command.languageCode()))
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
    }

    @Override
    public String getSupportedCommandText(String languageCode) {
        return null;
    }
}
