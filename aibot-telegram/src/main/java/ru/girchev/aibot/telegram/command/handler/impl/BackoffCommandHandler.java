package ru.girchev.aibot.telegram.command.handler.impl;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import ru.girchev.aibot.common.command.ICommand;
import ru.girchev.aibot.telegram.TelegramBot;
import ru.girchev.aibot.telegram.command.TelegramCommand;
import ru.girchev.aibot.telegram.command.TelegramCommandType;
import ru.girchev.aibot.common.service.MessageLocalizationService;
import ru.girchev.aibot.telegram.command.handler.AbstractTelegramCommandHandlerWithResponseSend;
import ru.girchev.aibot.telegram.command.handler.TelegramSupportedCommandProvider;
import ru.girchev.aibot.telegram.service.TypingIndicatorService;

import java.util.Objects;
import java.util.stream.Collectors;

public class BackoffCommandHandler extends AbstractTelegramCommandHandlerWithResponseSend {

    private final ObjectProvider<TelegramSupportedCommandProvider> handlersProvider;

    public BackoffCommandHandler(ObjectProvider<TelegramBot> telegramBotProvider,
                                 TypingIndicatorService typingIndicatorService,
                                 MessageLocalizationService messageLocalizationService,
                                 ObjectProvider<TelegramSupportedCommandProvider> handlersProvider) {
        super(telegramBotProvider, typingIndicatorService, messageLocalizationService);
        this.handlersProvider = handlersProvider;
    }

    @Override
    public int priority() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public boolean canHandle(ICommand<TelegramCommandType> command) {
        return command instanceof TelegramCommand;
    }

    @Override
    public String handleInner(TelegramCommand command) {
        telegramBotProvider.getObject().clearStatus(command.userId());
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
