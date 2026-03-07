package ru.girchev.aibot.telegram;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.ActionType;
import org.telegram.telegrambots.meta.api.methods.AnswerInlineQuery;
import org.telegram.telegrambots.meta.api.methods.polls.SendPoll;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.inlinequery.InlineQuery;
import org.telegram.telegrambots.meta.api.objects.inlinequery.inputmessagecontent.InputTextMessageContent;
import org.telegram.telegrambots.meta.api.objects.inlinequery.result.InlineQueryResultArticle;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ru.girchev.aibot.common.model.Attachment;
import ru.girchev.aibot.common.service.CommandSyncService;
import ru.girchev.aibot.telegram.command.TelegramCommand;
import ru.girchev.aibot.telegram.command.TelegramCommandType;
import ru.girchev.aibot.telegram.config.FileUploadProperties;
import ru.girchev.aibot.telegram.config.TelegramProperties;
import ru.girchev.aibot.telegram.model.TelegramUser;
import ru.girchev.aibot.telegram.model.TelegramUserSession;
import ru.girchev.aibot.telegram.service.TelegramFileService;
import ru.girchev.aibot.telegram.service.TelegramUserService;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Getter
public class TelegramBot extends TelegramLongPollingBot {

    private final TelegramProperties config;
    private final CommandSyncService commandSyncService;
    private final TelegramUserService userService;
    private final ObjectProvider<TelegramFileService> fileServiceProvider;
    private final ObjectProvider<FileUploadProperties> fileUploadPropertiesProvider;

    public TelegramBot(TelegramProperties config, 
                       CommandSyncService commandSyncService, 
                       TelegramUserService userService) {
        this(config, commandSyncService, userService, null, null);
    }

    public TelegramBot(TelegramProperties config, 
                       CommandSyncService commandSyncService, 
                       TelegramUserService userService,
                       ObjectProvider<TelegramFileService> fileServiceProvider,
                       ObjectProvider<FileUploadProperties> fileUploadPropertiesProvider) {
        this.config = config;
        this.commandSyncService = commandSyncService;
        this.userService = userService;
        this.fileServiceProvider = fileServiceProvider;
        this.fileUploadPropertiesProvider = fileUploadPropertiesProvider;
    }

    @Override
    public String getBotToken() {
        return config.getToken();
    }

    @Override
    public String getBotUsername() {
        return config.getUsername();
    }

    @Override
    public void clearWebhook() {
        // Пустая реализация, так как используем Long Polling
    }

    @Override
    public void onUpdateReceived(Update update) {
        TelegramCommand internalTelegramCommandWrap = null;
        try {
            if (update.hasCallbackQuery()) {
                internalTelegramCommandWrap = mapToTelegramCommand(update);
            } else if (update.hasMessage() && update.getMessage().hasText()) {
                internalTelegramCommandWrap = mapToTelegramTextCommand(update);
            } else if (update.hasMessage() && update.getMessage().hasPhoto() && isFileUploadEnabled()) {
                internalTelegramCommandWrap = mapToTelegramPhotoCommand(update);
            } else if (update.hasMessage() && update.getMessage().hasDocument() && isFileUploadEnabled()) {
                internalTelegramCommandWrap = mapToTelegramDocumentCommand(update);
            } else {
                log.warn("Unsupported message {}", update);
                return;
            }

            commandSyncService.syncAndHandle(internalTelegramCommandWrap);
        } catch (Exception e) {
            log.error("Internal error with handling message", e);
            try {
                if (internalTelegramCommandWrap != null) {
                    Integer replyToMessageId = null;
                    if (update.hasMessage() && update.getMessage() != null) {
                        replyToMessageId = update.getMessage().getMessageId();
                    } else if (update.hasCallbackQuery()) {
                        var maybeMessage = update.getCallbackQuery().getMessage();
                        if (maybeMessage instanceof Message message) {
                            replyToMessageId = message.getMessageId();
                        }
                    }
                    sendErrorMessage(internalTelegramCommandWrap.telegramId(), "Произошла непредвиденная ошибка", replyToMessageId);
                }
            } catch (TelegramApiException ex) {
                log.error("Exception on sending response to telegram", ex);
                throw new RuntimeException(ex);
            }
        }
    }

    /**
     * Проверяет, включена ли загрузка файлов.
     */
    private boolean isFileUploadEnabled() {
        if (fileUploadPropertiesProvider == null) {
            return false;
        }
        FileUploadProperties props = fileUploadPropertiesProvider.getIfAvailable();
        return props != null && Boolean.TRUE.equals(props.getEnabled());
    }

    protected TelegramCommand mapToTelegramCommand(Update update) {
        CallbackQuery cq = update.getCallbackQuery();
        var message = cq.getMessage();
        TelegramUser telegramUser = userService.getOrCreateUser(cq.getFrom());
        Long userId = telegramUser.getId();

        TelegramCommandType telegramCommandType = null;
        
        // Определяем команду по callback data
        String callbackData = cq.getData();
        if (callbackData != null) {
            if (callbackData.startsWith("THREADS_")) {
                // Обрабатываем callback query для /threads
                telegramCommandType = new TelegramCommandType(TelegramCommand.THREADS);
            } else if ("ERROR".equals(callbackData) || "IMPROVEMENT".equals(callbackData)) {
                // Обрабатываем callback query для /bugreport
                telegramCommandType = new TelegramCommandType(TelegramCommand.BUGREPORT);
            }
        }
        
        // Если команда не определена по callback data, используем botStatus из сессии
        if (telegramCommandType == null) {
            TelegramUserSession session = userService.getOrCreateSession(cq.getFrom());
            if (session.getBotStatus() != null) {
                telegramCommandType = new TelegramCommandType(session.getBotStatus());
            }
        }
        
        return new TelegramCommand(userId, message.getChatId(), telegramCommandType, update, true);
    }

    protected TelegramCommand mapToTelegramTextCommand(Update update) {
        var message = update.getMessage();
        TelegramUser telegramUser = userService.getOrCreateUser(message.getFrom());
        Long userId = telegramUser.getId();

        String userText;
        String stripped = message.getText().strip();
        TelegramCommandType telegramCommandType;

        if (stripped.startsWith("/")) {
            clearStatus(telegramUser.getTelegramId());
            int spaceIndex = stripped.indexOf(' ');
            String commandText = stripped.substring(0, spaceIndex == -1 ? stripped.length() : spaceIndex);
            telegramCommandType = new TelegramCommandType(commandText);
            userText = stripped.replace(commandText, "");
        } else {
            TelegramUserSession session = userService.getOrCreateSession(update.getMessage().getFrom());
            if (session.getBotStatus() != null) {
                telegramCommandType = new TelegramCommandType(session.getBotStatus());
            } else {
                telegramCommandType = new TelegramCommandType(TelegramCommand.MESSAGE);
            }
            userText = stripped;
        }
        return new TelegramCommand(userId, message.getChatId(), telegramCommandType, update, userText, true);
    }

    /**
     * Обрабатывает сообщение с фотографией.
     * Скачивает фото и сохраняет в MinIO.
     */
    public TelegramCommand mapToTelegramPhotoCommand(Update update) {
        var message = update.getMessage();
        TelegramUser telegramUser = userService.getOrCreateUser(message.getFrom());
        Long userId = telegramUser.getId();

        // Caption используется как userText
        String userText = message.getCaption() != null ? message.getCaption() : "";
        TelegramCommandType telegramCommandType = new TelegramCommandType(TelegramCommand.MESSAGE);

        List<Attachment> attachments = new ArrayList<>();

        try {
            TelegramFileService fileService = fileServiceProvider.getObject();
            Attachment attachment = fileService.processPhoto(message.getPhoto());
            attachments.add(attachment);
            log.info("Photo processed for user {}: key={}", userId, attachment.key());
        } catch (Exception e) {
            log.error("Error processing photo for user {}", userId, e);
            // Создаем команду без attachment, но с сообщением об ошибке
            return new TelegramCommand(userId, message.getChatId(), telegramCommandType, update, 
                    userText + " [Ошибка загрузки фото: " + e.getMessage() + "]", true, new ArrayList<>());
        }

        return new TelegramCommand(userId, message.getChatId(), telegramCommandType, update, 
                userText, true, attachments);
    }

    /**
     * Обрабатывает сообщение с документом (PDF).
     * Скачивает документ и сохраняет в MinIO.
     */
    public TelegramCommand mapToTelegramDocumentCommand(Update update) {
        var message = update.getMessage();
        TelegramUser telegramUser = userService.getOrCreateUser(message.getFrom());
        Long userId = telegramUser.getId();

        // Caption используется как userText
        String userText = message.getCaption() != null ? message.getCaption() : "";
        TelegramCommandType telegramCommandType = new TelegramCommandType(TelegramCommand.MESSAGE);

        List<Attachment> attachments = new ArrayList<>();

        try {
            TelegramFileService fileService = fileServiceProvider.getObject();
            Attachment attachment = fileService.processDocument(message.getDocument());
            
            if (attachment != null) {
                attachments.add(attachment);
                log.info("Document processed for user {}: key={}, mimeType={}", 
                        userId, attachment.key(), attachment.mimeType());
            } else {
                log.warn("Unsupported document type for user {}: {}", 
                        userId, message.getDocument().getMimeType());
                return new TelegramCommand(userId, message.getChatId(), telegramCommandType, update, 
                        userText + " [Неподдерживаемый тип файла: " + message.getDocument().getMimeType() + "]", 
                        true, new ArrayList<>());
            }
        } catch (Exception e) {
            log.error("Error processing document for user {}", userId, e);
            return new TelegramCommand(userId, message.getChatId(), telegramCommandType, update, 
                    userText + " [Ошибка загрузки документа: " + e.getMessage() + "]", true, new ArrayList<>());
        }

        return new TelegramCommand(userId, message.getChatId(), telegramCommandType, update, 
                userText, true, attachments);
    }

    public void sendMessage(Long chatId, String text) throws TelegramApiException {
        sendMessage(chatId, text, null);
    }

    public void sendMessage(Long chatId, String text, Integer replyToMessageId) throws TelegramApiException {
        sendMessageAndGetId(chatId, text, replyToMessageId);
    }

    public Integer sendMessageAndGetId(Long chatId, String text, Integer replyToMessageId) throws TelegramApiException {
        try {
            SendMessage message = new SendMessage();
            message.setChatId(chatId.toString());
            message.setText(text);
            message.setParseMode("HTML");
            if (replyToMessageId != null) {
                message.setReplyToMessageId(replyToMessageId);
            }
            Message sentMessage = execute(message);
            return sentMessage != null ? sentMessage.getMessageId() : null;
        } catch (TelegramApiException e) {
            // Если ошибка парсинга HTML, пробуем отправить без форматирования
            if (e.getMessage() != null && e.getMessage().contains("parse")) {
                log.warn("HTML parsing error, sending message without formatting: {}", e.getMessage());
                try {
                    SendMessage fallbackMessage = new SendMessage();
                    fallbackMessage.setChatId(chatId.toString());
                    fallbackMessage.setText(text);
                    if (replyToMessageId != null) {
                        fallbackMessage.setReplyToMessageId(replyToMessageId);
                    }
                    // Не устанавливаем parseMode для fallback
                    Message sentMessage = execute(fallbackMessage);
                    return sentMessage != null ? sentMessage.getMessageId() : null;
                } catch (TelegramApiException fallbackException) {
                    log.error("Error sending fallback message", fallbackException);
                    throw fallbackException;
                }
            }
            log.error("Error sending message", e);
            throw e;
        }
    }

    public void sendErrorMessage(Long chatId, String errorMessage) throws TelegramApiException {
        sendErrorMessage(chatId, errorMessage, null);
    }

    public void sendErrorMessage(Long chatId, String errorMessage, Integer replyToMessageId) throws TelegramApiException {
        try {
            SendMessage message = new SendMessage();
            message.setChatId(chatId.toString());
            message.setText(errorMessage);
            message.setParseMode("HTML");
            if (replyToMessageId != null) {
                message.setReplyToMessageId(replyToMessageId);
            }
            execute(message);
        } catch (TelegramApiException e) {
            // Если ошибка парсинга HTML, пробуем отправить без форматирования
            if (e.getMessage() != null && e.getMessage().contains("parse")) {
                log.warn("HTML parsing error, sending error message without formatting: {}", e.getMessage());
                try {
                    SendMessage fallbackMessage = new SendMessage();
                    fallbackMessage.setChatId(chatId.toString());
                    fallbackMessage.setText(errorMessage);
                    if (replyToMessageId != null) {
                        fallbackMessage.setReplyToMessageId(replyToMessageId);
                    }
                    // Не устанавливаем parseMode для fallback
                    execute(fallbackMessage);
                    return;
                } catch (TelegramApiException fallbackException) {
                    log.error("Error sending fallback error message", fallbackException);
                    throw fallbackException;
                }
            }
            log.error("Error sending error message", e);
            throw e;
        }
    }

    public void showTyping(Long chatId) throws TelegramApiException {
        SendChatAction action = new SendChatAction();
        action.setChatId(chatId.toString());
        action.setAction(ActionType.TYPING);
        execute(action);
    }

    // TODO add
    public void sendPhoto(Long chatId, InputFile photoFile, String caption) throws TelegramApiException {
        SendPhoto photo = new SendPhoto();
        photo.setChatId(chatId);
        photo.setPhoto(photoFile);
        photo.setCaption(caption);
        execute(photo);
    }

    // TODO add
    public void sendDocument(Long chatId, InputFile inputFile) throws TelegramApiException {
        SendDocument doc = new SendDocument();
        doc.setChatId(chatId);
        doc.setDocument(inputFile);

        execute(doc);
    }

    // TODO add
    public void sendPoll(Long chatId) throws TelegramApiException {
        SendPoll poll = new SendPoll();
        poll.setChatId(chatId);
        poll.setQuestion("2 + 2 = ?");
        poll.setOptions(List.of("3", "4", "5"));
        poll.setCorrectOptionId(1);
        poll.setType("quiz");

        execute(poll);
    }

    // TODO add
    public void sendWebPage(String chatId) throws TelegramApiException {
        WebAppInfo webApp = new WebAppInfo();
        webApp.setUrl("https://example.com/app");

        InlineKeyboardButton btn = new InlineKeyboardButton();
        btn.setText("Открыть приложение");
        btn.setWebApp(webApp);

        InlineKeyboardMarkup markup =
                new InlineKeyboardMarkup(List.of(List.of(btn)));

        SendMessage msg = new SendMessage(chatId, "Открываем Web App");
        msg.setReplyMarkup(markup);

        execute(msg);
    }

    // TODO
    public void onUpdateReceivedForInlineMode(Update update) {
        if (update.hasInlineQuery()) {
            InlineQuery query = update.getInlineQuery();

            InlineQueryResultArticle result =
                    new InlineQueryResultArticle();
            result.setId("1");
            result.setTitle("Ответ");
            result.setInputMessageContent(
                    new InputTextMessageContent("Текст ответа")
            );

            AnswerInlineQuery answer = new AnswerInlineQuery();
            answer.setInlineQueryId(query.getId());
            answer.setResults(List.of(result));

            try {
                execute(answer);
            } catch (TelegramApiException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public void clearStatus(Long chatId) {
        userService.tryToGetSession(chatId)
                .filter(s -> !StringUtils.isBlank(s.getBotStatus()))
                .ifPresent(s -> userService.updateUserSession(s.getTelegramUser(), null));
    }

    /**
     * Устанавливает меню команд для бота
     * @param commands список команд с описаниями
     * @throws TelegramApiException если не удалось установить команды
     */
    public void setMyCommands(List<BotCommand> commands) throws TelegramApiException {
        try {
            SetMyCommands setMyCommands = new SetMyCommands();
            setMyCommands.setCommands(commands);
            execute(setMyCommands);
            log.info("Successfully set {} commands for bot menu", commands.size());
        } catch (TelegramApiException e) {
            log.error("Error setting bot commands menu", e);
            throw e;
        }
    }
}