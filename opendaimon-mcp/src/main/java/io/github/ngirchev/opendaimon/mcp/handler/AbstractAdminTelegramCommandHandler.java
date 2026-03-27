package io.github.ngirchev.opendaimon.mcp.handler;

import io.github.ngirchev.opendaimon.bulkhead.exception.AccessDeniedException;
import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.bulkhead.service.IUserPriorityService;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.command.handler.AbstractTelegramCommandHandlerWithResponseSend;
import io.github.ngirchev.opendaimon.telegram.command.handler.TelegramCommandHandlerException;
import io.github.ngirchev.opendaimon.telegram.service.TypingIndicatorService;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

public abstract class AbstractAdminTelegramCommandHandler extends AbstractTelegramCommandHandlerWithResponseSend {

    protected final IUserPriorityService userPriorityService;

    protected AbstractAdminTelegramCommandHandler(
            ObjectProvider<TelegramBot> telegramBotProvider,
            TypingIndicatorService typingIndicatorService,
            MessageLocalizationService messageLocalizationService,
            IUserPriorityService userPriorityService) {
        super(telegramBotProvider, typingIndicatorService, messageLocalizationService);
        this.userPriorityService = userPriorityService;
    }

    @Override
    protected final String handleInner(TelegramCommand command) throws TelegramCommandHandlerException, TelegramApiException {
        UserPriority priority = userPriorityService.getUserPriority(command.userId());
        if (priority != UserPriority.ADMIN) {
            throw new AccessDeniedException("Admin access required for command: " + command.commandType());
        }
        return handleAdminInner(command);
    }

    protected abstract String handleAdminInner(TelegramCommand command) throws TelegramCommandHandlerException, TelegramApiException;
}
