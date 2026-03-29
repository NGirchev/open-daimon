package io.github.ngirchev.opendaimon.telegram;

import io.github.ngirchev.opendaimon.common.model.Attachment;
import io.github.ngirchev.opendaimon.common.model.AttachmentType;
import io.github.ngirchev.opendaimon.common.service.CommandSyncService;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.telegram.config.FileUploadProperties;
import io.github.ngirchev.opendaimon.telegram.config.TelegramProperties;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUserSession;
import io.github.ngirchev.opendaimon.telegram.service.TelegramFileService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramMessageCoalescingService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.messageorigin.MessageOriginChannel;
import org.telegram.telegrambots.meta.api.objects.messageorigin.MessageOriginHiddenUser;
import org.telegram.telegrambots.meta.api.objects.messageorigin.MessageOriginUser;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;

import java.util.ArrayList;
import java.util.List;

import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TelegramBot.
 * Covers getters, clearWebhook, onUpdateReceived, and mapTo* methods with mocks.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TelegramBotTest {

    @Mock
    private CommandSyncService commandSyncService;
    @Mock
    private TelegramUserService userService;
    @Mock
    private MessageLocalizationService messageLocalizationService;
    @Mock
    private ObjectProvider<TelegramFileService> fileServiceProvider;
    @Mock
    private ObjectProvider<FileUploadProperties> fileUploadPropertiesProvider;
    @Mock
    private ObjectProvider<TelegramMessageCoalescingService> coalescingServiceProvider;
    @Mock
    private TelegramMessageCoalescingService coalescingService;
    @Mock
    private TelegramFileService fileService;

    private TelegramProperties config;
    private TelegramBot bot;

    @BeforeEach
    void setUp() {
        config = new TelegramProperties();
        config.setToken("test-token");
        config.setUsername("test-bot");
        config.setMaxMessageLength(4096);
        config.parseWhitelistExceptions();
        bot = new TelegramBot(config, commandSyncService, userService);
    }

    @Test
    void getBotToken_returnsConfigToken() {
        assertEquals("test-token", bot.getBotToken());
    }

    @Test
    void getBotUsername_returnsConfigUsername() {
        assertEquals("test-bot", bot.getBotUsername());
    }

    @Test
    void clearWebhook_doesNotThrow() {
        assertDoesNotThrow(() -> bot.clearWebhook());
    }

    @Test
    void onUpdateReceived_whenUnsupportedUpdate_doesNotCallSyncAndHandle() {
        Update update = new Update();
        Message message = new Message();
        message.setMessageId(1);
        update.setMessage(message);
        // No text, no photo, no document -> mapUpdateToCommand returns null

        bot.onUpdateReceived(update);

        verify(commandSyncService, never()).syncAndHandle(any());
    }

    @Test
    void onUpdateReceived_whenMessageWithText_callsSyncAndHandle() {
        Update update = new Update();
        Message message = new Message();
        message.setMessageId(1);
        Chat chat = new Chat();
        chat.setId(100L);
        message.setChat(chat);
        User from = new User(200L, "testUser", false);
        message.setFrom(from);
        message.setText("/start");
        update.setMessage(message);

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setId(1L);
        telegramUser.setTelegramId(200L);
        telegramUser.setLanguageCode("en");
        when(userService.getOrCreateUser(any(User.class))).thenReturn(telegramUser);
        when(userService.getOrCreateSession(any(User.class))).thenReturn(new TelegramUserSession());

        bot.onUpdateReceived(update);

        verify(commandSyncService).syncAndHandle(any());
    }

    @Test
    void onUpdateReceived_whenCoalescingReturnsMerged_callsSyncAndHandleOnceWithMergedText() {
        TelegramBot coalescingBot = new TelegramBot(
                config,
                new DefaultBotOptions(),
                commandSyncService,
                userService,
                messageLocalizationService,
                fileServiceProvider,
                fileUploadPropertiesProvider,
                coalescingServiceProvider
        );
        when(coalescingServiceProvider.getIfAvailable()).thenReturn(coalescingService);
        when(coalescingService.isEnabled()).thenReturn(true);

        Update first = new Update();
        Message firstMessage = new Message();
        firstMessage.setMessageId(10);
        Chat firstChat = new Chat();
        firstChat.setId(100L);
        firstMessage.setChat(firstChat);
        User firstFrom = new User(200L, "u", false);
        firstMessage.setFrom(firstFrom);
        firstMessage.setText("Что тут?");
        first.setMessage(firstMessage);

        Update second = new Update();
        Message secondMessage = new Message();
        secondMessage.setMessageId(11);
        Chat secondChat = new Chat();
        secondChat.setId(100L);
        secondMessage.setChat(secondChat);
        User secondFrom = new User(200L, "u", false);
        secondMessage.setFrom(secondFrom);
        secondMessage.setText("Пересланный текст");
        MessageOriginUser origin = new MessageOriginUser();
        User sender = new User(300L, "Alice", false);
        origin.setSenderUser(sender);
        origin.setDate(1000);
        secondMessage.setForwardOrigin(origin);
        second.setMessage(secondMessage);

        when(coalescingService.onIncomingUpdate(eq(first), any()))
                .thenReturn(new TelegramMessageCoalescingService.ProcessMerged(first, second, "forward_origin"));

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setId(1L);
        telegramUser.setTelegramId(200L);
        telegramUser.setLanguageCode("en");
        when(userService.getOrCreateUser(any(User.class))).thenReturn(telegramUser);
        when(messageLocalizationService.getMessage("telegram.forward.prefix", "en", "Alice"))
                .thenReturn("[Forwarded from Alice]");

        coalescingBot.onUpdateReceived(first);

        ArgumentCaptor<io.github.ngirchev.opendaimon.common.command.ICommand> captor =
                ArgumentCaptor.forClass(io.github.ngirchev.opendaimon.common.command.ICommand.class);
        verify(commandSyncService, times(1)).syncAndHandle(captor.capture());
        assertInstanceOf(TelegramCommand.class, captor.getValue());
        TelegramCommand telegramCommand = (TelegramCommand) captor.getValue();
        assertEquals("Что тут?\n\n[Forwarded from Alice]\nПересланный текст", telegramCommand.userText());
    }

    @Test
    void onUpdateReceived_whenCoalescingReturnsPendingAndCurrent_processesBothUpdates() {
        TelegramBot coalescingBot = new TelegramBot(
                config,
                new DefaultBotOptions(),
                commandSyncService,
                userService,
                messageLocalizationService,
                fileServiceProvider,
                fileUploadPropertiesProvider,
                coalescingServiceProvider
        );
        when(coalescingServiceProvider.getIfAvailable()).thenReturn(coalescingService);
        when(coalescingService.isEnabled()).thenReturn(true);

        Update first = new Update();
        Message firstMessage = new Message();
        firstMessage.setMessageId(20);
        Chat firstChat = new Chat();
        firstChat.setId(100L);
        firstMessage.setChat(firstChat);
        User from = new User(200L, "u", false);
        firstMessage.setFrom(from);
        firstMessage.setText("Первый текст");
        first.setMessage(firstMessage);

        Update second = new Update();
        Message secondMessage = new Message();
        secondMessage.setMessageId(21);
        Chat secondChat = new Chat();
        secondChat.setId(100L);
        secondMessage.setChat(secondChat);
        User fromSecond = new User(200L, "u", false);
        secondMessage.setFrom(fromSecond);
        secondMessage.setText("Второй текст");
        second.setMessage(secondMessage);

        when(coalescingService.onIncomingUpdate(eq(first), any()))
                .thenReturn(new TelegramMessageCoalescingService.ProcessPendingAndCurrent(first, second, "no_merge"));

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setId(1L);
        telegramUser.setTelegramId(200L);
        telegramUser.setLanguageCode("en");
        when(userService.getOrCreateUser(any(User.class))).thenReturn(telegramUser);
        TelegramUserSession session = new TelegramUserSession();
        when(userService.getOrCreateSession(any(User.class))).thenReturn(session);

        coalescingBot.onUpdateReceived(first);

        verify(commandSyncService, times(2)).syncAndHandle(any());
    }

    @Test
    void mapToTelegramTextCommand_returnsCommandWithUserText() {
        Update update = new Update();
        Message message = new Message();
        message.setMessageId(1);
        Chat chat = new Chat();
        chat.setId(100L);
        message.setChat(chat);
        User from = new User(200L, "u", false);
        message.setFrom(from);
        message.setText("Hello");
        update.setMessage(message);

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setId(1L);
        telegramUser.setTelegramId(200L);
        telegramUser.setLanguageCode("en");
        when(userService.getOrCreateUser(any(User.class))).thenReturn(telegramUser);
        TelegramUserSession session = new TelegramUserSession();
        session.setBotStatus(null);
        when(userService.getOrCreateSession(any(User.class))).thenReturn(session);

        var command = bot.mapToTelegramTextCommand(update);

        assertNotNull(command);
        assertEquals(1L, command.userId());
        assertEquals(100L, command.telegramId()); // telegramId in command is chatId
        assertEquals("Hello", command.userText());
        assertEquals("en", command.languageCode());
    }

    @Test
    void mapToTelegramCommand_whenCallbackQuery_returnsCommand() {
        Update update = new Update();
        org.telegram.telegrambots.meta.api.objects.CallbackQuery cq = new org.telegram.telegrambots.meta.api.objects.CallbackQuery();
        cq.setId("cq1");
        cq.setData("ROLE_DEFAULT");
        User from = new User(200L, "u", false);
        cq.setFrom(from);
        Message msg = new Message();
        msg.setMessageId(1);
        Chat chat = new Chat();
        chat.setId(100L);
        msg.setChat(chat);
        cq.setMessage(msg);
        update.setCallbackQuery(cq);

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setId(1L);
        telegramUser.setTelegramId(200L);
        telegramUser.setLanguageCode("en");
        when(userService.getOrCreateUser(any(User.class))).thenReturn(telegramUser);
        TelegramUserSession session = new TelegramUserSession();
        session.setBotStatus(null);
        when(userService.getOrCreateSession(any(User.class))).thenReturn(session);

        var command = bot.mapToTelegramCommand(update);

        assertNotNull(command);
        assertEquals(1L, command.userId());
        assertEquals("en", command.languageCode());
    }

    @Test
    void onUpdateReceived_whenSyncAndHandleThrows_callsSendErrorReplyIfPossible() throws Exception {
        Update update = new Update();
        Message message = new Message();
        message.setMessageId(1);
        Chat chat = new Chat();
        chat.setId(100L);
        message.setChat(chat);
        User from = new User(200L, "u", false);
        message.setFrom(from);
        message.setText("Hi");
        update.setMessage(message);

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setId(1L);
        telegramUser.setTelegramId(200L);
        telegramUser.setLanguageCode("en");
        when(userService.getOrCreateUser(any(User.class))).thenReturn(telegramUser);
        when(userService.getOrCreateSession(any(User.class))).thenReturn(new TelegramUserSession());
        doThrow(new RuntimeException("sync failed")).when(commandSyncService).syncAndHandle(any());

        TelegramBot spyBot = spy(bot);
        doNothing().when(spyBot).sendErrorMessage(anyLong(), anyString(), any());

        spyBot.onUpdateReceived(update);

        verify(commandSyncService).syncAndHandle(any());
        verify(spyBot).sendErrorMessage(eq(100L), anyString(), eq(1));
    }

    @Test
    void clearStatus_whenSessionHasBotStatus_clearsIt() {
        TelegramUser user = new TelegramUser();
        user.setTelegramId(200L);
        TelegramUserSession session = new TelegramUserSession();
        session.setTelegramUser(user);
        session.setBotStatus("/role");
        when(userService.tryToGetSession(100L)).thenReturn(java.util.Optional.of(session));

        bot.clearStatus(100L);

        verify(userService).updateUserSession(eq(user), isNull());
    }

    @Test
    void clearStatus_whenSessionHasNoBotStatus_doesNotUpdate() {
        TelegramUserSession session = new TelegramUserSession();
        session.setBotStatus(null);
        when(userService.tryToGetSession(100L)).thenReturn(java.util.Optional.of(session));

        bot.clearStatus(100L);

        verify(userService, never()).updateUserSession(any(), any());
    }

    @Test
    void setMyCommands_withLanguageCode_executesWithLanguage() throws Exception {
        List<BotCommand> commands = List.of(new BotCommand("start", "Start bot"));
        TelegramBot spyBot = spy(bot);
        doReturn(null).when(spyBot).execute(any(SetMyCommands.class));

        spyBot.setMyCommands(commands, "ru");

        verify(spyBot).execute(any(SetMyCommands.class));
    }

    @Test
    void setMyCommands_withoutLanguageCode_executesDefault() throws Exception {
        List<BotCommand> commands = List.of(new BotCommand("start", "Start"));
        TelegramBot spyBot = spy(bot);
        doReturn(null).when(spyBot).execute(any(SetMyCommands.class));

        spyBot.setMyCommands(commands, (String) null);

        verify(spyBot).execute(any(SetMyCommands.class));
    }

    @Test
    void mapToTelegramPhotoCommand_whenNoCaptionAndPhotoProcessed_usesFallbackPrompt() throws Exception {
        TelegramBot photoBot = new TelegramBot(config, new DefaultBotOptions(), commandSyncService, userService,
                messageLocalizationService, fileServiceProvider, fileUploadPropertiesProvider);

        Update update = new Update();
        Message message = new Message();
        message.setMessageId(1);
        Chat chat = new Chat();
        chat.setId(100L);
        message.setChat(chat);
        User from = new User(200L, "u", false);
        message.setFrom(from);
        // caption is null — no text from the user
        PhotoSize photo = new PhotoSize();
        photo.setFileId("file1");
        photo.setFileSize(1000);
        photo.setWidth(100);
        photo.setHeight(100);
        message.setPhoto(new ArrayList<>(List.of(photo)));
        update.setMessage(message);

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setId(1L);
        telegramUser.setTelegramId(200L);
        telegramUser.setLanguageCode("en");
        when(userService.getOrCreateUser(any(User.class))).thenReturn(telegramUser);
        when(messageLocalizationService.getMessage("telegram.photo.default.prompt", "en")).thenReturn("What is this?");
        Attachment attachment = new Attachment("key1", "image/jpeg", "photo.jpg", 1000, AttachmentType.IMAGE, new byte[0]);
        when(fileServiceProvider.getObject()).thenReturn(fileService);
        when(fileService.processPhoto(any())).thenReturn(attachment);

        var command = photoBot.mapToTelegramPhotoCommand(update);

        assertNotNull(command);
        assertEquals("What is this?", command.userText());
    }

    @Test
    void mapToTelegramPhotoCommand_whenCaptionProvided_usesCaptionAsPrompt() throws Exception {
        TelegramBot photoBot = new TelegramBot(config, new DefaultBotOptions(), commandSyncService, userService,
                messageLocalizationService, fileServiceProvider, fileUploadPropertiesProvider);

        Update update = new Update();
        Message message = new Message();
        message.setMessageId(1);
        Chat chat = new Chat();
        chat.setId(100L);
        message.setChat(chat);
        User from = new User(200L, "u", false);
        message.setFrom(from);
        message.setCaption("Describe this image");
        PhotoSize photo = new PhotoSize();
        photo.setFileId("file1");
        photo.setFileSize(1000);
        photo.setWidth(100);
        photo.setHeight(100);
        message.setPhoto(new ArrayList<>(List.of(photo)));
        update.setMessage(message);

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setId(1L);
        telegramUser.setTelegramId(200L);
        telegramUser.setLanguageCode("en");
        when(userService.getOrCreateUser(any(User.class))).thenReturn(telegramUser);
        Attachment attachment = new Attachment("key1", "image/jpeg", "photo.jpg", 1000, AttachmentType.IMAGE, new byte[0]);
        when(fileServiceProvider.getObject()).thenReturn(fileService);
        when(fileService.processPhoto(any())).thenReturn(attachment);

        var command = photoBot.mapToTelegramPhotoCommand(update);

        assertNotNull(command);
        assertEquals("Describe this image", command.userText());
    }

    // ── Document default prompt tests ──────────────────────────────────

    @Test
    void mapToTelegramDocumentCommand_whenNoCaptionAndEnglish_usesLocalizedFallbackPrompt() throws Exception {
        TelegramBot docBot = new TelegramBot(config, new DefaultBotOptions(), commandSyncService, userService,
                messageLocalizationService, fileServiceProvider, fileUploadPropertiesProvider);

        Update update = new Update();
        Message message = new Message();
        message.setMessageId(1);
        Chat chat = new Chat();
        chat.setId(100L);
        message.setChat(chat);
        User from = new User(200L, "u", false);
        message.setFrom(from);
        Document document = new Document();
        document.setFileId("fileDoc1");
        document.setMimeType("application/pdf");
        document.setFileName("report.pdf");
        document.setFileSize(5000L);
        message.setDocument(document);
        update.setMessage(message);

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setId(1L);
        telegramUser.setTelegramId(200L);
        telegramUser.setLanguageCode("en");
        when(userService.getOrCreateUser(any(User.class))).thenReturn(telegramUser);
        when(messageLocalizationService.getMessage("telegram.document.default.prompt", "en"))
                .thenReturn("Analyze this document and provide a brief summary.");
        Attachment attachment = new Attachment("key1", "application/pdf", "report.pdf", 5000, AttachmentType.PDF, new byte[0]);
        when(fileServiceProvider.getObject()).thenReturn(fileService);
        when(fileService.processDocument(any())).thenReturn(attachment);

        var command = docBot.mapToTelegramDocumentCommand(update);

        assertNotNull(command);
        assertEquals("Analyze this document and provide a brief summary.", command.userText());
        assertEquals("en", command.languageCode());
    }

    @Test
    void mapToTelegramDocumentCommand_whenNoCaptionAndRussian_usesLocalizedFallbackPrompt() throws Exception {
        TelegramBot docBot = new TelegramBot(config, new DefaultBotOptions(), commandSyncService, userService,
                messageLocalizationService, fileServiceProvider, fileUploadPropertiesProvider);

        Update update = new Update();
        Message message = new Message();
        message.setMessageId(1);
        Chat chat = new Chat();
        chat.setId(100L);
        message.setChat(chat);
        User from = new User(200L, "u", false);
        message.setFrom(from);
        Document document = new Document();
        document.setFileId("fileDoc2");
        document.setMimeType("application/pdf");
        document.setFileName("отчет.pdf");
        document.setFileSize(3000L);
        message.setDocument(document);
        update.setMessage(message);

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setId(1L);
        telegramUser.setTelegramId(200L);
        telegramUser.setLanguageCode("ru");
        when(userService.getOrCreateUser(any(User.class))).thenReturn(telegramUser);
        when(messageLocalizationService.getMessage("telegram.document.default.prompt", "ru"))
                .thenReturn("Проанализируй этот документ и дай краткое описание.");
        Attachment attachment = new Attachment("key2", "application/pdf", "отчет.pdf", 3000, AttachmentType.PDF, new byte[0]);
        when(fileServiceProvider.getObject()).thenReturn(fileService);
        when(fileService.processDocument(any())).thenReturn(attachment);

        var command = docBot.mapToTelegramDocumentCommand(update);

        assertNotNull(command);
        assertEquals("Проанализируй этот документ и дай краткое описание.", command.userText());
        assertEquals("ru", command.languageCode());
    }

    @Test
    void mapToTelegramDocumentCommand_whenCaptionProvided_usesCaptionAsPrompt() throws Exception {
        TelegramBot docBot = new TelegramBot(config, new DefaultBotOptions(), commandSyncService, userService,
                messageLocalizationService, fileServiceProvider, fileUploadPropertiesProvider);

        Update update = new Update();
        Message message = new Message();
        message.setMessageId(1);
        Chat chat = new Chat();
        chat.setId(100L);
        message.setChat(chat);
        User from = new User(200L, "u", false);
        message.setFrom(from);
        message.setCaption("Find the main conclusions in this document");
        Document document = new Document();
        document.setFileId("fileDoc3");
        document.setMimeType("application/pdf");
        document.setFileName("thesis.pdf");
        document.setFileSize(10000L);
        message.setDocument(document);
        update.setMessage(message);

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setId(1L);
        telegramUser.setTelegramId(200L);
        telegramUser.setLanguageCode("en");
        when(userService.getOrCreateUser(any(User.class))).thenReturn(telegramUser);
        Attachment attachment = new Attachment("key3", "application/pdf", "thesis.pdf", 10000, AttachmentType.PDF, new byte[0]);
        when(fileServiceProvider.getObject()).thenReturn(fileService);
        when(fileService.processDocument(any())).thenReturn(attachment);

        var command = docBot.mapToTelegramDocumentCommand(update);

        assertNotNull(command);
        assertEquals("Find the main conclusions in this document", command.userText());
    }

    // ── Forwarded message tests ──────────────────────────────────

    @Test
    void mapToTelegramTextCommand_whenForwardedFromUser_enrichesUserTextWithForwardContext() {
        Update update = new Update();
        Message message = new Message();
        message.setMessageId(1);
        Chat chat = new Chat();
        chat.setId(100L);
        message.setChat(chat);
        User from = new User(200L, "u", false);
        message.setFrom(from);
        message.setText("Some forwarded text");

        User sender = new User(300L, "John", false);
        sender.setLastName("Doe");
        sender.setUserName("johndoe");
        MessageOriginUser origin = new MessageOriginUser();
        origin.setSenderUser(sender);
        origin.setDate(1000);
        message.setForwardOrigin(origin);
        update.setMessage(message);

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setId(1L);
        telegramUser.setTelegramId(200L);
        telegramUser.setLanguageCode("en");
        when(userService.getOrCreateUser(any(User.class))).thenReturn(telegramUser);

        var command = bot.mapToTelegramTextCommand(update);

        assertNotNull(command);
        assertEquals(TelegramCommand.MESSAGE, command.commandType().command());
        assertTrue(command.userText().contains("Some forwarded text"));
        assertTrue(command.userText().contains("John Doe (@johndoe)"));
        assertEquals("John Doe (@johndoe)", command.forwardedFrom());
    }

    @Test
    void mapToTelegramTextCommand_whenForwardedFromChannel_enrichesUserTextWithChannelTitle() {
        Update update = new Update();
        Message message = new Message();
        message.setMessageId(1);
        Chat chat = new Chat();
        chat.setId(100L);
        message.setChat(chat);
        User from = new User(200L, "u", false);
        message.setFrom(from);
        message.setText("Channel post content");

        Chat channelChat = new Chat();
        channelChat.setId(-1001234L);
        channelChat.setTitle("My Channel");
        MessageOriginChannel origin = new MessageOriginChannel();
        origin.setChat(channelChat);
        origin.setDate(1000);
        message.setForwardOrigin(origin);
        update.setMessage(message);

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setId(1L);
        telegramUser.setTelegramId(200L);
        telegramUser.setLanguageCode("en");
        when(userService.getOrCreateUser(any(User.class))).thenReturn(telegramUser);

        var command = bot.mapToTelegramTextCommand(update);

        assertNotNull(command);
        assertEquals(TelegramCommand.MESSAGE, command.commandType().command());
        assertTrue(command.userText().contains("Channel post content"));
        assertTrue(command.userText().contains("My Channel"));
        assertEquals("My Channel", command.forwardedFrom());
    }

    @Test
    void mapToTelegramTextCommand_whenForwardedFromHiddenUser_enrichesUserText() {
        Update update = new Update();
        Message message = new Message();
        message.setMessageId(1);
        Chat chat = new Chat();
        chat.setId(100L);
        message.setChat(chat);
        User from = new User(200L, "u", false);
        message.setFrom(from);
        message.setText("Hidden user message");

        MessageOriginHiddenUser origin = new MessageOriginHiddenUser();
        origin.setSenderUserName("Secret Person");
        origin.setDate(1000);
        message.setForwardOrigin(origin);
        update.setMessage(message);

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setId(1L);
        telegramUser.setTelegramId(200L);
        telegramUser.setLanguageCode("en");
        when(userService.getOrCreateUser(any(User.class))).thenReturn(telegramUser);

        var command = bot.mapToTelegramTextCommand(update);

        assertNotNull(command);
        assertEquals(TelegramCommand.MESSAGE, command.commandType().command());
        assertTrue(command.userText().contains("Hidden user message"));
        assertTrue(command.userText().contains("Secret Person"));
        assertEquals("Secret Person", command.forwardedFrom());
    }

    @Test
    void mapToTelegramTextCommand_whenForwardedCommandText_treatedAsMessageNotCommand() {
        Update update = new Update();
        Message message = new Message();
        message.setMessageId(1);
        Chat chat = new Chat();
        chat.setId(100L);
        message.setChat(chat);
        User from = new User(200L, "u", false);
        message.setFrom(from);
        message.setText("/start");

        User sender = new User(300L, "Alice", false);
        MessageOriginUser origin = new MessageOriginUser();
        origin.setSenderUser(sender);
        origin.setDate(1000);
        message.setForwardOrigin(origin);
        update.setMessage(message);

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setId(1L);
        telegramUser.setTelegramId(200L);
        telegramUser.setLanguageCode("en");
        when(userService.getOrCreateUser(any(User.class))).thenReturn(telegramUser);

        var command = bot.mapToTelegramTextCommand(update);

        assertNotNull(command);
        // Forwarded "/start" should NOT be treated as a bot command
        assertEquals(TelegramCommand.MESSAGE, command.commandType().command());
        assertTrue(command.userText().contains("/start"));
        assertEquals("Alice", command.forwardedFrom());
    }

    @Test
    void mapToTelegramTextCommand_whenNotForwarded_noForwardedFromSet() {
        Update update = new Update();
        Message message = new Message();
        message.setMessageId(1);
        Chat chat = new Chat();
        chat.setId(100L);
        message.setChat(chat);
        User from = new User(200L, "u", false);
        message.setFrom(from);
        message.setText("Regular message");
        update.setMessage(message);

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setId(1L);
        telegramUser.setTelegramId(200L);
        telegramUser.setLanguageCode("en");
        when(userService.getOrCreateUser(any(User.class))).thenReturn(telegramUser);
        TelegramUserSession session = new TelegramUserSession();
        when(userService.getOrCreateSession(any(User.class))).thenReturn(session);

        var command = bot.mapToTelegramTextCommand(update);

        assertNotNull(command);
        assertNull(command.forwardedFrom());
        assertEquals("Regular message", command.userText());
    }

    @Test
    void mapToTelegramPhotoCommand_whenForwarded_enrichesUserTextWithForwardContext() throws Exception {
        TelegramBot photoBot = new TelegramBot(config, new DefaultBotOptions(), commandSyncService, userService,
                messageLocalizationService, fileServiceProvider, fileUploadPropertiesProvider);

        Update update = new Update();
        Message message = new Message();
        message.setMessageId(1);
        Chat chat = new Chat();
        chat.setId(100L);
        message.setChat(chat);
        User from = new User(200L, "u", false);
        message.setFrom(from);
        message.setCaption("Check this photo");
        PhotoSize photo = new PhotoSize();
        photo.setFileId("file1");
        photo.setFileSize(1000);
        photo.setWidth(100);
        photo.setHeight(100);
        message.setPhoto(new ArrayList<>(List.of(photo)));

        User sender = new User(300L, "Bob", false);
        MessageOriginUser origin = new MessageOriginUser();
        origin.setSenderUser(sender);
        origin.setDate(1000);
        message.setForwardOrigin(origin);
        update.setMessage(message);

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setId(1L);
        telegramUser.setTelegramId(200L);
        telegramUser.setLanguageCode("en");
        when(userService.getOrCreateUser(any(User.class))).thenReturn(telegramUser);
        when(messageLocalizationService.getMessage("telegram.forward.prefix", "en", "Bob"))
                .thenReturn("[Forwarded from Bob]");
        Attachment attachment = new Attachment("key1", "image/jpeg", "photo.jpg", 1000, AttachmentType.IMAGE, new byte[0]);
        when(fileServiceProvider.getObject()).thenReturn(fileService);
        when(fileService.processPhoto(any())).thenReturn(attachment);

        var command = photoBot.mapToTelegramPhotoCommand(update);

        assertNotNull(command);
        assertTrue(command.userText().contains("[Forwarded from Bob]"));
        assertTrue(command.userText().contains("Check this photo"));
        assertEquals("Bob", command.forwardedFrom());
    }

    @Test
    void extractForwardInfo_whenChannelWithSignature_returnsChannelTitleAndSignature() {
        Message message = new Message();
        Chat channelChat = new Chat();
        channelChat.setId(-1001234L);
        channelChat.setTitle("News Channel");
        MessageOriginChannel origin = new MessageOriginChannel();
        origin.setChat(channelChat);
        origin.setAuthorSignature("Editor");
        origin.setDate(1000);
        message.setForwardOrigin(origin);

        String result = bot.extractForwardInfo(message);

        assertEquals("News Channel (Editor)", result);
    }

    @Test
    void extractForwardInfo_whenNotForwarded_returnsNull() {
        Message message = new Message();

        String result = bot.extractForwardInfo(message);

        assertNull(result);
    }

    @Test
    void enrichWithForwardContext_whenForwardInfoNull_returnsOriginalText() {
        String result = bot.enrichWithForwardContext("Hello", null, "en");
        assertEquals("Hello", result);
    }

    @Test
    void enrichWithForwardContext_whenNoLocalizationService_usesFallback() {
        String result = bot.enrichWithForwardContext("Hello", "Alice", "en");
        assertEquals("[Forwarded from Alice]\nHello", result);
    }

    @Test
    void mapToTelegramDocumentCommand_whenBlankCaption_usesLocalizedFallbackPrompt() throws Exception {
        TelegramBot docBot = new TelegramBot(config, new DefaultBotOptions(), commandSyncService, userService,
                messageLocalizationService, fileServiceProvider, fileUploadPropertiesProvider);

        Update update = new Update();
        Message message = new Message();
        message.setMessageId(1);
        Chat chat = new Chat();
        chat.setId(100L);
        message.setChat(chat);
        User from = new User(200L, "u", false);
        message.setFrom(from);
        message.setCaption("   ");
        Document document = new Document();
        document.setFileId("fileDoc4");
        document.setMimeType("text/plain");
        document.setFileName("notes.txt");
        document.setFileSize(200L);
        message.setDocument(document);
        update.setMessage(message);

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setId(1L);
        telegramUser.setTelegramId(200L);
        telegramUser.setLanguageCode("en");
        when(userService.getOrCreateUser(any(User.class))).thenReturn(telegramUser);
        when(messageLocalizationService.getMessage("telegram.document.default.prompt", "en"))
                .thenReturn("Analyze this document and provide a brief summary.");
        Attachment attachment = new Attachment("key4", "text/plain", "notes.txt", 200, AttachmentType.PDF, new byte[0]);
        when(fileServiceProvider.getObject()).thenReturn(fileService);
        when(fileService.processDocument(any())).thenReturn(attachment);

        var command = docBot.mapToTelegramDocumentCommand(update);

        assertNotNull(command);
        assertEquals("Analyze this document and provide a brief summary.", command.userText());
    }

}
