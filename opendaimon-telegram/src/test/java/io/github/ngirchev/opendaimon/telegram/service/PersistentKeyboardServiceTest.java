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
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersistentKeyboardServiceTest {

    private static final long USER_ID = 1L;

    @Mock
    private CoreCommonProperties coreCommonProperties;
    @Mock
    private CoreCommonProperties.SummarizationProperties summarizationProperties;
    @Mock
    private UserRepository userRepository;

    private PersistentKeyboardService service;

    @BeforeEach
    void setUp() {
        ReloadableResourceBundleMessageSource messageSource = new ReloadableResourceBundleMessageSource();
        messageSource.setBasenames("classpath:messages/common", "classpath:messages/telegram");
        messageSource.setDefaultEncoding("UTF-8");
        MessageLocalizationService messageLocalizationService = new MessageLocalizationService(messageSource);

        ObjectProvider<TelegramBot> botProvider = mock(ObjectProvider.class);
        TelegramProperties telegramProperties = new TelegramProperties();
        telegramProperties.setToken("t");
        telegramProperties.setUsername("u");
        telegramProperties.setMaxMessageLength(4096);
        telegramProperties.getCommands().setModelEnabled(true);

        when(coreCommonProperties.getSummarization()).thenReturn(summarizationProperties);
        when(summarizationProperties.getMessageWindowSize()).thenReturn(20);
        when(summarizationProperties.getMaxWindowTokens()).thenReturn(8000);

        TelegramUser user = new TelegramUser();
        user.setLanguageCode("en");
        user.setPreferredModelId(null);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        service = new PersistentKeyboardService(
                coreCommonProperties,
                botProvider,
                telegramProperties,
                messageLocalizationService,
                userRepository);
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
}
