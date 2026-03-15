package io.github.ngirchev.opendaimon.telegram.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import io.github.ngirchev.opendaimon.common.event.SummarizationStartedEvent;
import io.github.ngirchev.opendaimon.common.model.User;
import io.github.ngirchev.opendaimon.common.repository.ConversationThreadRepository;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;

@Slf4j
@RequiredArgsConstructor
public class TelegramSummarizationListener {

    private final ObjectProvider<TelegramBot> telegramBotProvider;
    private final ConversationThreadRepository conversationThreadRepository;
    private final MessageLocalizationService messageLocalizationService;

    @EventListener
    public void onSummarizationStarted(SummarizationStartedEvent event) {
        try {
            var threadOpt = conversationThreadRepository.findByThreadKey(event.conversationId());
            if (threadOpt.isEmpty()) {
                return;
            }
            User user = threadOpt.get().getUser();
            if (!(user instanceof TelegramUser telegramUser)) {
                return;
            }
            TelegramBot bot = telegramBotProvider.getIfAvailable();
            if (bot == null) {
                return;
            }
            String text = messageLocalizationService.getMessage(
                    "telegram.summarization.started", telegramUser.getLanguageCode());
            bot.execute(new SendMessage(telegramUser.getTelegramId().toString(), text));
        } catch (Exception e) {
            log.warn("Failed to send summarization notification for conversationId={}: {}",
                    event.conversationId(), e.getMessage());
        }
    }
}
