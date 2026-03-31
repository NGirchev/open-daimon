package io.github.ngirchev.opendaimon.telegram.command;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.telegram.telegrambots.meta.api.objects.Update;
import io.github.ngirchev.opendaimon.common.command.IChatCommand;
import io.github.ngirchev.opendaimon.common.model.Attachment;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(fluent = true)
@NoArgsConstructor
public class TelegramCommand implements IChatCommand<TelegramCommandType> {

    // Command constants
    public static final String START = "/start";
    public static final String ROLE = "/role";
    public static final String MESSAGE = "/message";
    public static final String BUGREPORT = "/bugreport";
    public static final String NEWTHREAD = "/newthread";
    public static final String HISTORY = "/history";
    public static final String THREADS = "/threads";
    public static final String LANGUAGE = "/language";
    public static final String MODEL = "/model";
    public static final String MODEL_KEYBOARD_PREFIX = "🤖";
    public static final String CONTEXT_KEYBOARD_PREFIX = "💬";

    private Long userId;
    private Long telegramId;
    private TelegramCommandType commandType; // may be null
    private Update update; // original message
    private String userText; // may be null for callback query
    private boolean stream;
    private List<Attachment> attachments = new ArrayList<>();
    /** User language code (e.g. from Telegram), for localization. */
    private String languageCode;
    /** Source description for forwarded messages (e.g. user name, channel title). Null if not forwarded. */
    private String forwardedFrom;

    public TelegramCommand(Long userId, Long chatId, TelegramCommandType telegramCommandType, Update update) {
        this.userId = userId;
        this.telegramId = chatId;
        this.commandType = telegramCommandType;
        this.update = update;
        this.stream = false;
    }

    public TelegramCommand(Long userId, Long chatId, TelegramCommandType telegramCommandType, Update update, boolean stream) {
        this.userId = userId;
        this.telegramId = chatId;
        this.commandType = telegramCommandType;
        this.update = update;
        this.stream = stream;
    }

    public TelegramCommand(Long userId, Long chatId, TelegramCommandType telegramCommandType, Update update, String userText) {
        this.userId = userId;
        this.telegramId = chatId;
        this.commandType = telegramCommandType;
        this.update = update;
        this.userText = userText;
    }

    public TelegramCommand(Long userId, Long chatId, TelegramCommandType telegramCommandType, Update update, 
                           String userText, boolean stream) {
        this.userId = userId;
        this.telegramId = chatId;
        this.commandType = telegramCommandType;
        this.update = update;
        this.userText = userText;
        this.stream = stream;
    }

    public TelegramCommand(Long userId, Long chatId, TelegramCommandType telegramCommandType, Update update, 
                           String userText, boolean stream, List<Attachment> attachments) {
        this.userId = userId;
        this.telegramId = chatId;
        this.commandType = telegramCommandType;
        this.update = update;
        this.userText = userText;
        this.stream = stream;
        this.attachments = attachments != null ? attachments : new ArrayList<>();
    }

    /**
     * Returns whether the command has attachments.
     */
    public boolean hasAttachments() {
        return attachments != null && !attachments.isEmpty();
    }

    /**
     * Adds an attachment to the command.
     */
    public TelegramCommand addAttachment(Attachment attachment) {
        if (this.attachments == null) {
            this.attachments = new ArrayList<>();
        }
        this.attachments.add(attachment);
        return this;
    }
}
