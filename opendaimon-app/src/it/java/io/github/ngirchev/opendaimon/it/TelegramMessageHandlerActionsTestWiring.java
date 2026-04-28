package io.github.ngirchev.opendaimon.it;

import io.github.ngirchev.fsm.impl.extended.ExDomainFsm;
import io.github.ngirchev.opendaimon.common.ai.pipeline.AIRequestPipeline;
import io.github.ngirchev.opendaimon.common.service.AIGatewayRegistry;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.common.service.OpenDaimonMessageService;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.MessageTelegramCommandHandler;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.MessageHandlerContext;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.MessageHandlerEvent;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.MessageHandlerFsmFactory;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.MessageHandlerState;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.TelegramMessageHandlerActions;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.TelegramMessageSender;
import io.github.ngirchev.opendaimon.telegram.config.TelegramProperties;
import io.github.ngirchev.opendaimon.telegram.service.ChatSettingsService;
import io.github.ngirchev.opendaimon.telegram.service.PersistentKeyboardService;
import io.github.ngirchev.opendaimon.telegram.service.ReplyImageAttachmentService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramAgentStreamView;
import io.github.ngirchev.opendaimon.telegram.service.TelegramChatPacerImpl;
import io.github.ngirchev.opendaimon.telegram.service.TelegramMessageService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserSessionService;
import io.github.ngirchev.opendaimon.telegram.service.TypingIndicatorService;
import org.springframework.beans.factory.ObjectProvider;

public final class TelegramMessageHandlerActionsTestWiring {

    private TelegramMessageHandlerActionsTestWiring() {
    }

    public static MessageTelegramCommandHandler create(
            ObjectProvider<TelegramBot> telegramBotProvider,
            TypingIndicatorService typingIndicatorService,
            MessageLocalizationService messageLocalizationService,
            TelegramUserService telegramUserService,
            TelegramUserSessionService telegramUserSessionService,
            TelegramMessageService telegramMessageService,
            AIGatewayRegistry aiGatewayRegistry,
            OpenDaimonMessageService messageService,
            AIRequestPipeline aiRequestPipeline,
            TelegramProperties telegramProperties,
            ChatSettingsService chatSettingsService,
            PersistentKeyboardService persistentKeyboardService,
            ReplyImageAttachmentService replyImageAttachmentService) {
        var telegramChatPacer = new TelegramChatPacerImpl(telegramProperties);
        TelegramMessageSender messageSender = new TelegramMessageSender(
                telegramBotProvider, messageLocalizationService, persistentKeyboardService, telegramChatPacer);
        TelegramAgentStreamView agentStreamView = new TelegramAgentStreamView(
                messageSender, telegramChatPacer, telegramProperties);
        TelegramMessageHandlerActions actions = new TelegramMessageHandlerActions(
                telegramUserService, telegramUserSessionService, telegramMessageService,
                aiGatewayRegistry, messageService, aiRequestPipeline, telegramProperties,
                chatSettingsService, persistentKeyboardService, replyImageAttachmentService,
                messageSender, null, agentStreamView, 10, false);
        ExDomainFsm<MessageHandlerContext, MessageHandlerState, MessageHandlerEvent> handlerFsm =
                MessageHandlerFsmFactory.create(actions);
        return new MessageTelegramCommandHandler(
                telegramBotProvider,
                typingIndicatorService,
                messageLocalizationService,
                handlerFsm,
                telegramMessageService,
                telegramProperties,
                persistentKeyboardService);
    }
}
