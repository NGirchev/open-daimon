package io.github.ngirchev.opendaimon.telegram.service;

import io.github.ngirchev.opendaimon.common.config.CoreCommonProperties;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.telegram.config.TelegramProperties;
import io.github.ngirchev.opendaimon.common.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersistentKeyboardServiceTest {

    private static final long USER_ID = 1L;
    private static final long GROUP_CHAT_ID = -5267226692L;

    @Mock
    private CoreCommonProperties coreCommonProperties;
    @Mock
    private CoreCommonProperties.SummarizationProperties summarizationProperties;
    @Mock
    private UserRepository userRepository;
    @Mock
    private TelegramChatPacer telegramChatPacer;
    @Mock
    private ObjectProvider<TelegramBot> botProvider;
    @Mock
    private TelegramBot telegramBot;

    private PersistentKeyboardService service;

    @BeforeEach
    void setUp() {
        ReloadableResourceBundleMessageSource messageSource = new ReloadableResourceBundleMessageSource();
        messageSource.setBasenames("classpath:messages/common", "classpath:messages/telegram");
        messageSource.setDefaultEncoding("UTF-8");
        MessageLocalizationService messageLocalizationService = new MessageLocalizationService(messageSource);

        TelegramProperties telegramProperties = new TelegramProperties();
        telegramProperties.setToken("t");
        telegramProperties.setUsername("u");
        telegramProperties.setMaxMessageLength(4096);
        telegramProperties.getCommands().setModelEnabled(true);

        when(coreCommonProperties.getSummarization()).thenReturn(summarizationProperties);
        when(summarizationProperties.getMessageWindowSize()).thenReturn(20);
        when(summarizationProperties.getMaxWindowTokens()).thenReturn(8000);
        try {
            lenient().when(telegramChatPacer.reserve(org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }

        TelegramUser user = new TelegramUser();
        user.setLanguageCode("en");
        user.setPreferredModelId(null);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        service = new PersistentKeyboardService(
                coreCommonProperties,
                botProvider,
                telegramProperties,
                messageLocalizationService,
                userRepository,
                telegramChatPacer);
    }

    /**
     * {@code is_persistent=true} makes Telegram Android keep the custom keyboard in a mode where
     * the user often cannot use the UI back control to return to the normal text keyboard.
     */
    @Test
    void buildKeyboardMarkup_doesNotSetIsPersistent_soUserCanDismissCustomKeyboard() {
        ConversationThread thread = new ConversationThread();
        thread.setTotalMessages(0);
        thread.setMessagesAtLastSummarization(0);
        thread.setTotalTokens(0L);

        ReplyKeyboardMarkup markup = service.buildKeyboardMarkup(USER_ID, thread);

        assertNotNull(markup);
        assertFalse(
                Boolean.TRUE.equals(markup.getIsPersistent()),
                "ReplyKeyboardMarkup.is_persistent must stay false (default) for normal IME back behavior on Telegram Android");
    }

    @Test
    void sendKeyboard_waitsOneChatPacingIntervalAfterStreamBeforeSkipping() throws Exception {
        ConversationThread thread = new ConversationThread();
        thread.setTotalMessages(8);
        thread.setMessagesAtLastSummarization(0);
        thread.setTotalTokens(0L);
        when(summarizationProperties.getMessageWindowSize()).thenReturn(100);
        when(botProvider.getObject()).thenReturn(telegramBot);
        when(telegramChatPacer.intervalMs(GROUP_CHAT_ID)).thenReturn(3000L);

        service.sendKeyboard(GROUP_CHAT_ID, USER_ID, thread, "z-ai/glm-4.5v");

        verify(telegramChatPacer).reserve(GROUP_CHAT_ID, 4000L);
        org.mockito.ArgumentCaptor<SendMessage> messageCaptor =
                org.mockito.ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramBot).execute(messageCaptor.capture());
        SendMessage message = messageCaptor.getValue();
        assertEquals(Long.toString(GROUP_CHAT_ID), message.getChatId());
        assertEquals("🤖 z-ai/glm-4.5v  ·  💬 8%", message.getText());
    }
}
