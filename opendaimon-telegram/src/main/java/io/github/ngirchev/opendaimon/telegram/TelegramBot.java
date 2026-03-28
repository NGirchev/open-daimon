package io.github.ngirchev.opendaimon.telegram;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.bots.DefaultBotOptions;
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
import org.telegram.telegrambots.meta.api.objects.messageorigin.MessageOrigin;
import org.telegram.telegrambots.meta.api.objects.messageorigin.MessageOriginChannel;
import org.telegram.telegrambots.meta.api.objects.messageorigin.MessageOriginChat;
import org.telegram.telegrambots.meta.api.objects.messageorigin.MessageOriginHiddenUser;
import org.telegram.telegrambots.meta.api.objects.messageorigin.MessageOriginUser;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeChat;
import org.telegram.telegrambots.meta.api.objects.inlinequery.InlineQuery;
import org.telegram.telegrambots.meta.api.objects.inlinequery.inputmessagecontent.InputTextMessageContent;
import org.telegram.telegrambots.meta.api.objects.inlinequery.result.InlineQueryResultArticle;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import io.github.ngirchev.opendaimon.common.model.Attachment;
import io.github.ngirchev.opendaimon.common.service.CommandSyncService;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommandType;
import io.github.ngirchev.opendaimon.telegram.config.FileUploadProperties;
import io.github.ngirchev.opendaimon.telegram.config.TelegramProperties;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUserSession;
import io.github.ngirchev.opendaimon.telegram.service.TelegramFileService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserService;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Slf4j
@Getter
public class TelegramBot extends TelegramLongPollingBot {

    private static final int DEBUG_TEXT_PREVIEW_LIMIT = 400;

    private final TelegramProperties config;
    private final CommandSyncService commandSyncService;
    private final TelegramUserService userService;
    private final MessageLocalizationService messageLocalizationService;
    private final ObjectProvider<TelegramFileService> fileServiceProvider;
    private final ObjectProvider<FileUploadProperties> fileUploadPropertiesProvider;

    public TelegramBot(TelegramProperties config,
                       CommandSyncService commandSyncService,
                       TelegramUserService userService) {
        this(config, new DefaultBotOptions(), commandSyncService, userService, null, null, null);
    }

    /**
     * Constructor with DefaultBotOptions for long polling timeouts (socket timeout, getUpdates timeout).
     */
    public TelegramBot(TelegramProperties config,
                       DefaultBotOptions botOptions,
                       CommandSyncService commandSyncService,
                       TelegramUserService userService,
                       MessageLocalizationService messageLocalizationService,
                       ObjectProvider<TelegramFileService> fileServiceProvider,
                       ObjectProvider<FileUploadProperties> fileUploadPropertiesProvider) {
        super(botOptions, config.getToken());
        this.config = config;
        this.commandSyncService = commandSyncService;
        this.userService = userService;
        this.messageLocalizationService = messageLocalizationService;
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
        // No-op: we use long polling
    }

    @Override
    public void onUpdateReceived(Update update) {
        logIncomingUpdateDebug(update);
        TelegramCommand command = mapUpdateToCommand(update);
        if (command == null) return;
        try {
            commandSyncService.syncAndHandle(command);
        } catch (Exception e) {
            log.error("Internal error with handling message", e);
            sendErrorReplyIfPossible(update, command);
        }
    }

    private void logIncomingUpdateDebug(Update update) {
        if (!log.isDebugEnabled()) {
            return;
        }
        if (update == null) {
            log.debug("Telegram update snapshot: update is null");
            return;
        }

        Message message = update.getMessage();
        CallbackQuery callbackQuery = update.getCallbackQuery();
        Message callbackMessage = callbackQuery != null && callbackQuery.getMessage() instanceof Message cbMessage
                ? cbMessage
                : null;

        org.telegram.telegrambots.meta.api.objects.User from = message != null
                ? message.getFrom()
                : callbackQuery != null ? callbackQuery.getFrom() : null;
        org.telegram.telegrambots.meta.api.objects.Chat chat = message != null
                ? message.getChat()
                : callbackMessage != null ? callbackMessage.getChat() : null;

        String text = message != null ? message.getText() : null;
        String caption = message != null ? message.getCaption() : null;
        String callbackData = callbackQuery != null ? callbackQuery.getData() : null;
        boolean hasText = message != null && message.hasText();
        boolean hasCaption = StringUtils.isNotBlank(caption);
        boolean startsWithSlash = hasText && text.strip().startsWith("/");
        String forwardOriginType = message != null && message.getForwardOrigin() != null
                ? message.getForwardOrigin().getClass().getSimpleName()
                : null;
        Integer replyToMessageId = message != null && message.getReplyToMessage() != null
                ? message.getReplyToMessage().getMessageId()
                : null;

        log.debug(
                "Telegram update snapshot: updateId={}, kind={}, chatId={}, chatType={}, messageId={}, fromId={}, fromUsername={}, fromIsBot={}, replyToMessageId={}, forwardOriginType={}, hasText={}, hasCaption={}, hasEntities={}, hasPhoto={}, hasDocument={}, hasCallback={}, hasInlineQuery={}, startsWithSlash={}, entities={}, textLength={}, text='{}', captionLength={}, caption='{}', callbackLength={}, callback='{}'",
                update.getUpdateId(),
                resolveUpdateKind(update),
                chat != null ? chat.getId() : null,
                chat != null ? chat.getType() : null,
                message != null ? message.getMessageId() : callbackMessage != null ? callbackMessage.getMessageId() : null,
                from != null ? from.getId() : null,
                from != null ? from.getUserName() : null,
                from != null ? from.getIsBot() : null,
                replyToMessageId,
                forwardOriginType,
                hasText,
                hasCaption,
                message != null && message.hasEntities(),
                message != null && message.hasPhoto(),
                message != null && message.hasDocument(),
                callbackQuery != null,
                update.hasInlineQuery(),
                startsWithSlash,
                formatEntities(message),
                text != null ? text.length() : null,
                trimForLog(text),
                caption != null ? caption.length() : null,
                trimForLog(caption),
                callbackData != null ? callbackData.length() : null,
                trimForLog(callbackData)
        );
    }

    private String resolveUpdateKind(Update update) {
        if (update.hasMessage()) {
            return "message";
        }
        if (update.hasCallbackQuery()) {
            return "callback_query";
        }
        if (update.hasInlineQuery()) {
            return "inline_query";
        }
        if (update.hasChannelPost()) {
            return "channel_post";
        }
        if (update.hasEditedMessage()) {
            return "edited_message";
        }
        if (update.hasEditedChannelPost()) {
            return "edited_channel_post";
        }
        return "other";
    }

    private String formatEntities(Message message) {
        if (message == null || message.getEntities() == null || message.getEntities().isEmpty()) {
            return "[]";
        }
        return message.getEntities()
                .stream()
                .filter(Objects::nonNull)
                .map(entity -> entity.getType() + "@" + entity.getOffset() + ":" + entity.getLength())
                .toList()
                .toString();
    }

    private String trimForLog(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .strip();
        if (normalized.length() <= DEBUG_TEXT_PREVIEW_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, DEBUG_TEXT_PREVIEW_LIMIT) + "...(truncated)";
    }

    private TelegramCommand mapUpdateToCommand(Update update) {
        if (update.hasCallbackQuery()) {
            return mapToTelegramCommand(update);
        }
        if (update.hasMessage() && update.getMessage().hasText()) {
            return mapToTelegramTextCommand(update);
        }
        if (update.hasMessage() && update.getMessage().hasPhoto() && isFileUploadEnabled()) {
            return mapToTelegramPhotoCommand(update);
        }
        if (update.hasMessage() && update.getMessage().hasDocument() && isFileUploadEnabled()) {
            log.info("Document message received, routing to mapToTelegramDocumentCommand");
            return mapToTelegramDocumentCommand(update);
        }
        if (update.hasMessage() && (update.getMessage().hasPhoto() || update.getMessage().hasDocument())) {
            sendFileUploadDisabledReply(update);
        }
        log.warn("Unsupported message {}", update);
        return null;
    }

    private void sendFileUploadDisabledReply(Update update) {
        try {
            Long chatId = update.getMessage().getChatId();
            Integer messageId = update.getMessage().getMessageId();
            String langCode = null;
            try {
                TelegramUser user = userService.getOrCreateUser(update.getMessage().getFrom());
                langCode = user.getLanguageCode();
            } catch (Exception ignored) {
            }
            String msg = messageLocalizationService != null
                    ? messageLocalizationService.getMessage("telegram.error.file.upload.disabled", langCode)
                    : "File uploads are not supported.";
            sendErrorMessage(chatId, msg, messageId);
        } catch (TelegramApiException e) {
            log.error("Error sending file upload disabled reply", e);
        }
    }

    private void sendErrorReplyIfPossible(Update update, TelegramCommand command) {
        try {
            Integer replyToMessageId = getReplyToMessageId(update);
            String errMsg = messageLocalizationService != null
                    ? messageLocalizationService.getMessage("common.error.unexpected", command.languageCode())
                    : "An unexpected error occurred";
            sendErrorMessage(command.telegramId(), errMsg, replyToMessageId);
        } catch (TelegramApiException ex) {
            log.error("Exception on sending response to telegram", ex);
            throw new RuntimeException(ex);
        }
    }

    private static Integer getReplyToMessageId(Update update) {
        if (update.hasMessage() && update.getMessage() != null) {
            return update.getMessage().getMessageId();
        }
        if (update.hasCallbackQuery()) {
            var maybeMessage = update.getCallbackQuery().getMessage();
            if (maybeMessage instanceof Message message) {
                return message.getMessageId();
            }
        }
        return null;
    }

    /**
     * Returns whether file upload is enabled.
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
        String callbackData = cq.getData();
        if (callbackData != null) {
            if (callbackData.startsWith("THREADS_")) {
                telegramCommandType = new TelegramCommandType(TelegramCommand.THREADS);
            } else if (callbackData.startsWith("LANG_")) {
                telegramCommandType = new TelegramCommandType(TelegramCommand.LANGUAGE);
            } else if ("ERROR".equals(callbackData) || "IMPROVEMENT".equals(callbackData)) {
                telegramCommandType = new TelegramCommandType(TelegramCommand.BUGREPORT);
            } else if (callbackData.startsWith("MODEL_")) {
                telegramCommandType = new TelegramCommandType(TelegramCommand.MODEL);
            }
        }
        if (telegramCommandType == null) {
            TelegramUserSession session = userService.getOrCreateSession(cq.getFrom());
            if (session.getBotStatus() != null) {
                telegramCommandType = new TelegramCommandType(session.getBotStatus());
            }
        }

        TelegramCommand cmd = new TelegramCommand(userId, message.getChatId(), telegramCommandType, update, true);
        return cmd.languageCode(telegramUser.getLanguageCode());
    }

    protected TelegramCommand mapToTelegramTextCommand(Update update) {
        var message = update.getMessage();
        TelegramUser telegramUser = userService.getOrCreateUser(message.getFrom());
        Long userId = telegramUser.getId();

        String forwardInfo = extractForwardInfo(message);
        String userText;
        String stripped = message.getText().strip();
        TelegramCommandType telegramCommandType;

        if (forwardInfo != null) {
            // Forwarded messages are always treated as regular messages, not as commands
            telegramCommandType = new TelegramCommandType(TelegramCommand.MESSAGE);
            userText = enrichWithForwardContext(stripped, forwardInfo, telegramUser.getLanguageCode());
        } else if (stripped.startsWith("/")) {
            clearStatus(telegramUser.getTelegramId());
            int spaceIndex = stripped.indexOf(' ');
            String commandText = stripped.substring(0, spaceIndex == -1 ? stripped.length() : spaceIndex);
            telegramCommandType = new TelegramCommandType(commandText);
            userText = stripped.replace(commandText, "");
        } else if (stripped.startsWith(TelegramCommand.MODEL_KEYBOARD_PREFIX)
                || stripped.startsWith(TelegramCommand.CONTEXT_KEYBOARD_PREFIX)) {
            telegramCommandType = new TelegramCommandType(TelegramCommand.MODEL);
            userText = stripped;
        } else {
            TelegramUserSession session = userService.getOrCreateSession(update.getMessage().getFrom());
            if (session.getBotStatus() != null) {
                telegramCommandType = new TelegramCommandType(session.getBotStatus());
            } else {
                telegramCommandType = new TelegramCommandType(TelegramCommand.MESSAGE);
            }
            userText = stripped;
        }
        TelegramCommand cmd = new TelegramCommand(userId, message.getChatId(), telegramCommandType, update, userText, true);
        cmd.forwardedFrom(forwardInfo);
        return cmd.languageCode(telegramUser.getLanguageCode());
    }

    /**
     * Handles a message with a photo. Downloads the photo and saves it to MinIO.
     */
    public TelegramCommand mapToTelegramPhotoCommand(Update update) {
        var message = update.getMessage();
        TelegramUser telegramUser = userService.getOrCreateUser(message.getFrom());
        Long userId = telegramUser.getId();

        String forwardInfo = extractForwardInfo(message);
        String caption = message.getCaption();
        String baseText = caption != null && !caption.isBlank()
                ? caption
                : messageLocalizationService != null
                        ? messageLocalizationService.getMessage("telegram.photo.default.prompt", telegramUser.getLanguageCode())
                        : "What is this?";
        String userText = enrichWithForwardContext(baseText, forwardInfo, telegramUser.getLanguageCode());
        TelegramCommandType telegramCommandType = new TelegramCommandType(TelegramCommand.MESSAGE);

        List<Attachment> attachments = new ArrayList<>();

        try {
            TelegramFileService fileService = fileServiceProvider.getObject();
            Attachment attachment = fileService.processPhoto(message.getPhoto());
            attachments.add(attachment);
            log.info("Photo processed for user {}: key={}", userId, attachment.key());
        } catch (Exception e) {
            log.error("Error processing photo for user {}", userId, e);
            String errSuffix = messageLocalizationService != null
                    ? messageLocalizationService.getMessage("telegram.error.photo.load", telegramUser.getLanguageCode(), e.getMessage())
                    : " [Photo upload error: " + e.getMessage() + "]";
            TelegramCommand errCmd = new TelegramCommand(userId, message.getChatId(), telegramCommandType, update,
                    userText + errSuffix, true, new ArrayList<>());
            return errCmd.languageCode(telegramUser.getLanguageCode());
        }

        TelegramCommand cmd = new TelegramCommand(userId, message.getChatId(), telegramCommandType, update, userText, true, attachments);
        cmd.forwardedFrom(forwardInfo);
        return cmd.languageCode(telegramUser.getLanguageCode());
    }

    /**
     * Handles a message with a document (e.g. PDF). Downloads the document and saves it to MinIO.
     */
    public TelegramCommand mapToTelegramDocumentCommand(Update update) {
        var message = update.getMessage();
        TelegramUser telegramUser = userService.getOrCreateUser(message.getFrom());
        Long userId = telegramUser.getId();

        String forwardInfo = extractForwardInfo(message);
        String caption = message.getCaption();
        String baseText = caption != null && !caption.isBlank()
                ? caption
                : messageLocalizationService != null
                        ? messageLocalizationService.getMessage("telegram.document.default.prompt", telegramUser.getLanguageCode())
                        : "Analyze this document and provide a brief summary.";
        String userText = enrichWithForwardContext(baseText, forwardInfo, telegramUser.getLanguageCode());
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
                String errSuffix = messageLocalizationService != null
                        ? messageLocalizationService.getMessage("telegram.error.unsupported.file", telegramUser.getLanguageCode(), message.getDocument().getMimeType())
                        : " [Unsupported file type: " + message.getDocument().getMimeType() + "]";
                TelegramCommand errCmd = new TelegramCommand(userId, message.getChatId(), telegramCommandType, update, userText + errSuffix, true, new ArrayList<>());
                return errCmd.languageCode(telegramUser.getLanguageCode());
            }
        } catch (Exception e) {
            log.error("Error processing document for user {}", userId, e);
            String errSuffix = messageLocalizationService != null
                    ? messageLocalizationService.getMessage("telegram.error.document.load", telegramUser.getLanguageCode(), e.getMessage())
                    : " [Document upload error: " + e.getMessage() + "]";
            TelegramCommand errCmd = new TelegramCommand(userId, message.getChatId(), telegramCommandType, update, userText + errSuffix, true, new ArrayList<>());
            return errCmd.languageCode(telegramUser.getLanguageCode());
        }

        Attachment first = attachments.getFirst();
        log.info("Document command created: attachmentsCount={}, userText='{}', firstAttachmentType={}, dataLength={}",
                attachments.size(), userText, first != null ? first.type() : null, first != null && first.data() != null ? first.data().length : 0);
        TelegramCommand cmd = new TelegramCommand(userId, message.getChatId(), telegramCommandType, update, userText, true, attachments);
        cmd.forwardedFrom(forwardInfo);
        return cmd.languageCode(telegramUser.getLanguageCode());
    }

    public void sendMessage(Long chatId, String text) throws TelegramApiException {
        sendMessage(chatId, text, null);
    }

    public void sendMessage(Long chatId, String text, Integer replyToMessageId) throws TelegramApiException {
        sendMessageAndGetId(chatId, text, replyToMessageId, null);
    }

    public void sendMessage(Long chatId, String text, Integer replyToMessageId, ReplyKeyboard replyMarkup) throws TelegramApiException {
        sendMessageAndGetId(chatId, text, replyToMessageId, replyMarkup);
    }

    public Integer sendMessageAndGetId(Long chatId, String text, Integer replyToMessageId) throws TelegramApiException {
        return sendMessageAndGetId(chatId, text, replyToMessageId, null);
    }

    public Integer sendMessageAndGetId(Long chatId, String text, Integer replyToMessageId, ReplyKeyboard replyMarkup) throws TelegramApiException {
        try {
            SendMessage message = new SendMessage();
            message.setChatId(chatId.toString());
            message.setText(text);
            message.setParseMode("HTML");
            if (replyToMessageId != null) {
                message.setReplyToMessageId(replyToMessageId);
            }
            if (replyMarkup != null) {
                message.setReplyMarkup(replyMarkup);
            }
            Message sentMessage = execute(message);
            return sentMessage != null ? sentMessage.getMessageId() : null;
        } catch (TelegramApiException e) {
            if (e.getMessage() != null && e.getMessage().contains("parse")) {
                log.warn("HTML parsing error, sending message without formatting: {}", e.getMessage());
                try {
                    SendMessage fallbackMessage = new SendMessage();
                    fallbackMessage.setChatId(chatId.toString());
                    fallbackMessage.setText(text);
                    if (replyToMessageId != null) {
                        fallbackMessage.setReplyToMessageId(replyToMessageId);
                    }
                    if (replyMarkup != null) {
                        fallbackMessage.setReplyMarkup(replyMarkup);
                    }
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
            if (e.getMessage() != null && e.getMessage().contains("parse")) {
                log.warn("HTML parsing error, sending error message without formatting: {}", e.getMessage());
                try {
                    SendMessage fallbackMessage = new SendMessage();
                    fallbackMessage.setChatId(chatId.toString());
                    fallbackMessage.setText(errorMessage);
                    if (replyToMessageId != null) {
                        fallbackMessage.setReplyToMessageId(replyToMessageId);
                    }
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
        btn.setText("Open app");
        btn.setWebApp(webApp);

        InlineKeyboardMarkup markup =
                new InlineKeyboardMarkup(List.of(List.of(btn)));

        SendMessage msg = new SendMessage(chatId, "Opening Web App");
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
            result.setTitle("Reply");
            result.setInputMessageContent(
                    new InputTextMessageContent("Reply text")
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

    /**
     * Extracts a human-readable source description from a forwarded message's origin.
     * Returns null if the message is not forwarded.
     */
    String extractForwardInfo(Message message) {
        MessageOrigin origin = message.getForwardOrigin();
        if (origin == null) {
            return null;
        }
        if (origin instanceof MessageOriginUser userOrigin) {
            org.telegram.telegrambots.meta.api.objects.User sender = userOrigin.getSenderUser();
            StringBuilder name = new StringBuilder(sender.getFirstName());
            if (sender.getLastName() != null) {
                name.append(" ").append(sender.getLastName());
            }
            if (sender.getUserName() != null) {
                name.append(" (@").append(sender.getUserName()).append(")");
            }
            return name.toString();
        }
        if (origin instanceof MessageOriginChannel channelOrigin) {
            org.telegram.telegrambots.meta.api.objects.Chat chat = channelOrigin.getChat();
            String title = chat.getTitle();
            String signature = channelOrigin.getAuthorSignature();
            if (title != null && signature != null) {
                return title + " (" + signature + ")";
            }
            return title != null ? title : "channel";
        }
        if (origin instanceof MessageOriginHiddenUser hiddenOrigin) {
            String senderName = hiddenOrigin.getSenderUserName();
            return senderName != null ? senderName : "hidden user";
        }
        if (origin instanceof MessageOriginChat chatOrigin) {
            org.telegram.telegrambots.meta.api.objects.Chat chat = chatOrigin.getSenderChat();
            return chat.getTitle() != null ? chat.getTitle() : "chat";
        }
        return null;
    }

    /**
     * Prepends forwarding context to user text if the message is forwarded.
     */
    String enrichWithForwardContext(String userText, String forwardInfo, String languageCode) {
        if (forwardInfo == null) {
            return userText;
        }
        String prefix;
        if (messageLocalizationService != null) {
            prefix = messageLocalizationService.getMessage("telegram.forward.prefix", languageCode, forwardInfo);
        } else {
            prefix = "[Forwarded from " + forwardInfo + "]";
        }
        return userText != null ? prefix + "\n" + userText : prefix;
    }

    public void clearStatus(Long chatId) {
        userService.tryToGetSession(chatId)
                .filter(s -> !StringUtils.isBlank(s.getBotStatus()))
                .ifPresent(s -> userService.updateUserSession(s.getTelegramUser(), null));
    }

    /**
     * Sets bot command menu scoped to a specific chat, overriding the global language-based menu for that user.
     */
    public void setMyCommands(List<BotCommand> commands, Long chatId) throws TelegramApiException {
        SetMyCommands setMyCommands = new SetMyCommands();
        setMyCommands.setCommands(commands);
        BotCommandScopeChat scope = new BotCommandScopeChat();
        scope.setChatId(chatId.toString());
        setMyCommands.setScope(scope);
        execute(setMyCommands);
        log.info("Set {} commands for chat {} menu", commands.size(), chatId);
    }

    /**
     * Sets bot command menu for the given language. If languageCode is null, commands apply as default.
     *
     * @param commands     list of commands with descriptions
     * @param languageCode optional two-letter ISO 639-1 language code (e.g. ru, en)
     */
    public void setMyCommands(List<BotCommand> commands, String languageCode) throws TelegramApiException {
        try {
            SetMyCommands setMyCommands = new SetMyCommands();
            setMyCommands.setCommands(commands);
            if (languageCode != null && !languageCode.isBlank()) {
                setMyCommands.setLanguageCode(languageCode);
            }
            execute(setMyCommands);
            log.info("Successfully set {} commands for bot menu (language: {})", commands.size(), languageCode != null ? languageCode : "default");
        } catch (TelegramApiException e) {
            log.error("Error setting bot commands menu", e);
            throw e;
        }
    }
}
