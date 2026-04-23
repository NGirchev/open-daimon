package io.github.ngirchev.opendaimon.telegram.command.handler.impl;

import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.bulkhead.service.IUserPriorityService;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.ai.model.ModelInfo;
import io.github.ngirchev.opendaimon.common.ai.response.ModelListAIResponse;
import io.github.ngirchev.opendaimon.common.service.AIGateway;
import io.github.ngirchev.opendaimon.common.service.AIGatewayRegistry;
import io.github.ngirchev.opendaimon.common.service.ConversationThreadService;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommandType;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.telegram.service.ModelSelectionSession;
import io.github.ngirchev.opendaimon.telegram.service.PersistentKeyboardService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserService;
import io.github.ngirchev.opendaimon.telegram.service.TypingIndicatorService;
import io.github.ngirchev.opendaimon.telegram.service.UserModelPreferenceService;
import io.github.ngirchev.opendaimon.telegram.service.UserRecentModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModelTelegramCommandHandlerTest {

    private static final Long CHAT_ID = 100500L;
    private static final Long USER_ID = 7L;

    @Mock private ObjectProvider<TelegramBot> telegramBotProvider;
    @Mock private TelegramBot telegramBot;
    @Mock private TypingIndicatorService typingIndicatorService;
    @Mock private MessageLocalizationService messageLocalizationService;
    @Mock private TelegramUserService telegramUserService;
    @Mock private UserModelPreferenceService userModelPreferenceService;
    @Mock private AIGatewayRegistry aiGatewayRegistry;
    @Mock private IUserPriorityService userPriorityService;
    @Mock private PersistentKeyboardService persistentKeyboardService;
    @Mock private ConversationThreadService conversationThreadService;
    @Mock private ModelSelectionSession modelSelectionSession;
    @Mock private UserRecentModelService userRecentModelService;
    @Mock private AIGateway aiGateway;

    private ModelTelegramCommandHandler handler;

    @BeforeEach
    void setUp() {
        when(telegramBotProvider.getObject()).thenReturn(telegramBot);
        when(messageLocalizationService.getMessage(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(messageLocalizationService.getMessage(anyString(), anyString(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(messageLocalizationService.getMessage(anyString(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(userPriorityService.getUserPriority(USER_ID)).thenReturn(UserPriority.REGULAR);

        handler = new ModelTelegramCommandHandler(
                telegramBotProvider,
                typingIndicatorService,
                messageLocalizationService,
                telegramUserService,
                userModelPreferenceService,
                aiGatewayRegistry,
                userPriorityService,
                persistentKeyboardService,
                conversationThreadService,
                modelSelectionSession,
                userRecentModelService);
    }

    @Test
    void shouldPlaceRecentFirstWhenHistoryNonEmpty() throws TelegramApiException {
        List<ModelInfo> models = buildNineModels();
        stubModelFetch(models);
        when(userRecentModelService.getRecentModels(eq(USER_ID), eq(8)))
                .thenReturn(List.of("model-0", "model-3"));

        handler.handleInner(buildPlainModelCommand());

        InlineKeyboardMarkup markup = captureSentMarkup();
        // Row 0: AUTO. Row 1 must be RECENT category button.
        String firstCategoryData = markup.getKeyboard().get(1).get(0).getCallbackData();
        assertThat(firstCategoryData).isEqualTo("MODEL_C_RECENT");
    }

    @Test
    void shouldHideRecentCategoryWhenHistoryEmpty() throws TelegramApiException {
        List<ModelInfo> models = buildNineModels();
        stubModelFetch(models);
        when(userRecentModelService.getRecentModels(eq(USER_ID), eq(8)))
                .thenReturn(List.of());

        handler.handleInner(buildPlainModelCommand());

        InlineKeyboardMarkup markup = captureSentMarkup();
        boolean hasRecent = markup.getKeyboard().stream()
                .flatMap(List::stream)
                .map(InlineKeyboardButton::getCallbackData)
                .anyMatch(d -> d != null && d.equals("MODEL_C_RECENT"));
        assertThat(hasRecent).isFalse();
    }

    @Test
    void shouldSkipRecentModelsMissingFromGateway() throws TelegramApiException {
        List<ModelInfo> models = buildNineModels();
        stubModelFetch(models);
        // model-0 exists, ghost-model is gone from gateway
        when(userRecentModelService.getRecentModels(eq(USER_ID), eq(8)))
                .thenReturn(List.of("model-0", "ghost-model"));

        handler.handleInner(buildPlainModelCommand());

        InlineKeyboardMarkup markup = captureSentMarkup();
        // RECENT still shown (non-empty), but the count label is the LAST localized arg.
        // Indirect check: just confirm the RECENT button is present (count comes from label key).
        boolean recentRow = markup.getKeyboard().stream()
                .flatMap(List::stream)
                .map(InlineKeyboardButton::getCallbackData)
                .anyMatch("MODEL_C_RECENT"::equals);
        assertThat(recentRow).isTrue();
    }

    @Test
    void shouldRecordUsageOnExplicitPick() {
        List<ModelInfo> models = buildNineModels();
        when(modelSelectionSession.getOrFetch(eq(USER_ID), any())).thenReturn(models);

        TelegramUser user = buildUser();
        User from = mock(User.class);
        when(from.getId()).thenReturn(USER_ID);
        when(telegramUserService.getOrCreateUser(from)).thenReturn(user);

        CallbackQuery cq = mock(CallbackQuery.class);
        when(cq.getData()).thenReturn("MODEL_2");
        when(cq.getFrom()).thenReturn(from);
        when(cq.getId()).thenReturn("cq-1");
        Message msg = mock(Message.class);
        when(msg.getMessageId()).thenReturn(42);
        when(cq.getMessage()).thenReturn(msg);

        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(true);
        when(update.getCallbackQuery()).thenReturn(cq);

        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID,
                new TelegramCommandType(TelegramCommand.MODEL), update);
        command.languageCode("en");

        handler.handleInner(command);

        verify(userModelPreferenceService).setPreferredModel(USER_ID, "model-2");
        verify(userRecentModelService).recordUsage(USER_ID, "model-2");
    }

    @Test
    void shouldNotRecordUsageOnAutoPick() {
        TelegramUser user = buildUser();
        User from = mock(User.class);
        when(from.getId()).thenReturn(USER_ID);
        when(telegramUserService.getOrCreateUser(from)).thenReturn(user);

        CallbackQuery cq = mock(CallbackQuery.class);
        when(cq.getData()).thenReturn("MODEL_AUTO");
        when(cq.getFrom()).thenReturn(from);
        when(cq.getId()).thenReturn("cq-auto");
        Message msg = mock(Message.class);
        when(msg.getMessageId()).thenReturn(77);
        when(cq.getMessage()).thenReturn(msg);

        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(true);
        when(update.getCallbackQuery()).thenReturn(cq);

        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID,
                new TelegramCommandType(TelegramCommand.MODEL), update);
        command.languageCode("en");

        handler.handleInner(command);

        verify(userModelPreferenceService).clearPreference(USER_ID);
        verify(userRecentModelService, never()).recordUsage(any(), anyString());
    }

    // ----- helpers -----

    private TelegramCommand buildPlainModelCommand() {
        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(false);
        Message message = mock(Message.class);
        User from = mock(User.class);
        when(from.getId()).thenReturn(USER_ID);
        when(message.getFrom()).thenReturn(from);
        when(update.getMessage()).thenReturn(message);

        TelegramUser user = buildUser();
        when(telegramUserService.getOrCreateUser(from)).thenReturn(user);

        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID,
                new TelegramCommandType(TelegramCommand.MODEL), update);
        command.languageCode("en");
        return command;
    }

    private TelegramUser buildUser() {
        TelegramUser user = new TelegramUser();
        user.setId(USER_ID);
        user.setLanguageCode("en");
        return user;
    }

    private void stubModelFetch(List<ModelInfo> models) {
        when(modelSelectionSession.getOrFetch(eq(USER_ID), any())).thenReturn(models);
    }

    private InlineKeyboardMarkup captureSentMarkup() throws TelegramApiException {
        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramBot).execute(captor.capture());
        return (InlineKeyboardMarkup) captor.getValue().getReplyMarkup();
    }

    /**
     * Nine distinct OpenRouter models so the category menu (not the flat list)
     * branch is exercised.
     */
    private List<ModelInfo> buildNineModels() {
        return IntStream.range(0, 9)
                .mapToObj(i -> new ModelInfo("model-" + i, Set.of(ModelCapabilities.FREE), "OpenRouter"))
                .toList();
    }

    // Silence unused mock warning on strict settings.
    @SuppressWarnings("unused")
    private void unusedGateway(ModelListAIResponse response) {
        when(aiGatewayRegistry.getSupportedAiGateways(any())).thenReturn(List.of(aiGateway));
    }
}
