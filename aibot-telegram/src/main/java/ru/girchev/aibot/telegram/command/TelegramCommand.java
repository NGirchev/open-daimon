package ru.girchev.aibot.telegram.command;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.telegram.telegrambots.meta.api.objects.Update;
import ru.girchev.aibot.common.command.IChatCommand;
import ru.girchev.aibot.common.model.Attachment;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(fluent = true)
@NoArgsConstructor
public class TelegramCommand implements IChatCommand<TelegramCommandType> {

    // Константы команд
    public static final String START = "/start";
    public static final String ROLE = "/role";
    public static final String MESSAGE = "/message";
    public static final String BUGREPORT = "/bugreport";
    public static final String NEWTHREAD = "/newthread";
    public static final String HISTORY = "/history";
    public static final String THREADS = "/threads";

    private Long userId;
    private Long telegramId;
    private TelegramCommandType commandType; // может быть null
    private Update update; // original message
    private String userText; // может быть null для callback query
    private boolean stream;
    private List<Attachment> attachments = new ArrayList<>();

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
     * Проверяет, есть ли вложения в команде.
     */
    public boolean hasAttachments() {
        return attachments != null && !attachments.isEmpty();
    }

    /**
     * Добавляет вложение к команде.
     */
    public TelegramCommand addAttachment(Attachment attachment) {
        if (this.attachments == null) {
            this.attachments = new ArrayList<>();
        }
        this.attachments.add(attachment);
        return this;
    }
}
