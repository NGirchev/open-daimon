package io.github.ngirchev.opendaimon.telegram.service;

import lombok.RequiredArgsConstructor;
import org.telegram.telegrambots.meta.api.objects.Chat;
import io.github.ngirchev.opendaimon.common.model.User;
import io.github.ngirchev.opendaimon.telegram.model.TelegramGroup;

import java.util.Optional;

/**
 * Resolves the {@link User} that owns per-chat settings (language, preferred
 * model, agent mode, thinking mode, assistant role, menu version hash) for a
 * given Telegram update.
 * <ul>
 *   <li>Private chat → the invoker's {@code TelegramUser}.</li>
 *   <li>Group or supergroup → the {@link TelegramGroup} row keyed on {@code chat_id}.</li>
 * </ul>
 * Must be called once per incoming update — the result is cached on
 * {@code TelegramCommand.settingsOwner} for the duration of handler execution.
 */
@RequiredArgsConstructor
public class ChatSettingsOwnerResolver {

    private static final String GROUP = "group";
    private static final String SUPERGROUP = "supergroup";

    private final TelegramUserService telegramUserService;
    private final TelegramGroupService telegramGroupService;

    /**
     * Resolves the settings owner for an incoming update.
     *
     * @param chat    the chat the update originated in (never {@code null} for valid updates)
     * @param invoker the Telegram API user who produced the update (never {@code null})
     * @return group entity for group chats, user entity for private chats
     */
    public User resolveForChat(Chat chat, org.telegram.telegrambots.meta.api.objects.User invoker) {
        if (chat != null && isGroupLike(chat.getType())) {
            return telegramGroupService.getOrCreateGroup(chat);
        }
        return telegramUserService.getOrCreateUser(invoker);
    }

    /**
     * Looks up the settings owner by Telegram {@code chat_id} without creating
     * anything. Used by common-module paths (e.g. summarization) that only have
     * a chat id from a persisted {@code ConversationThread}.
     * <p>
     * Group chat ids are negative, user chat ids are positive — we try the
     * matching table first to keep this cheap.
     */
    public Optional<User> findByChatId(Long chatId) {
        if (chatId == null) {
            return Optional.empty();
        }
        if (chatId < 0) {
            return telegramGroupService.findByChatId(chatId).map(User.class::cast);
        }
        return telegramUserService.findByTelegramId(chatId).map(User.class::cast);
    }

    private static boolean isGroupLike(String chatType) {
        return GROUP.equalsIgnoreCase(chatType) || SUPERGROUP.equalsIgnoreCase(chatType);
    }
}
