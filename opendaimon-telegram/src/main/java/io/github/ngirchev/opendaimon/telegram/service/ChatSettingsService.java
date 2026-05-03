package io.github.ngirchev.opendaimon.telegram.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.github.ngirchev.opendaimon.common.model.AssistantRole;
import io.github.ngirchev.opendaimon.common.model.ThinkingMode;
import io.github.ngirchev.opendaimon.common.model.User;
import io.github.ngirchev.opendaimon.telegram.model.TelegramGroup;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;

import java.util.Optional;

/**
 * Polymorphic facade over per-chat settings mutations. Accepts a {@link User}
 * owner resolved by {@link ChatSettingsOwnerResolver} — a {@link TelegramUser}
 * for private chats, a {@link TelegramGroup} for group/supergroup chats —
 * and dispatches to the corresponding service.
 * <p>
 * Call-sites must use this facade instead of keying on
 * {@code cq.getFrom().getId()} or {@code user.getTelegramId()}; that keeps
 * group chats' settings consistent across members.
 */
@Slf4j
@RequiredArgsConstructor
public class ChatSettingsService {

    private final TelegramUserService telegramUserService;
    private final TelegramGroupService telegramGroupService;

    public void updateLanguageCode(User owner, String languageCode) {
        if (owner instanceof TelegramGroup group) {
            telegramGroupService.updateLanguageCode(group.getTelegramId(), languageCode);
        } else if (owner instanceof TelegramUser user) {
            telegramUserService.updateLanguageCode(user.getTelegramId(), languageCode);
        } else {
            throw unsupported(owner, "updateLanguageCode");
        }
    }

    public void updateAgentMode(User owner, boolean enabled) {
        if (owner instanceof TelegramGroup group) {
            telegramGroupService.updateAgentMode(group.getTelegramId(), enabled);
        } else if (owner instanceof TelegramUser user) {
            telegramUserService.updateAgentMode(user.getTelegramId(), enabled);
        } else {
            throw unsupported(owner, "updateAgentMode");
        }
    }

    public void updateThinkingMode(User owner, ThinkingMode mode) {
        if (owner instanceof TelegramGroup group) {
            telegramGroupService.updateThinkingMode(group.getTelegramId(), mode);
        } else if (owner instanceof TelegramUser user) {
            telegramUserService.updateThinkingMode(user.getTelegramId(), mode);
        } else {
            throw unsupported(owner, "updateThinkingMode");
        }
    }

    public void updateAssistantRole(User owner, String roleContent) {
        if (owner instanceof TelegramGroup group) {
            telegramGroupService.updateAssistantRole(group.getTelegramId(), roleContent);
        } else if (owner instanceof TelegramUser user) {
            telegramUserService.updateAssistantRole(toTelegramApiUser(user), roleContent);
        } else {
            throw unsupported(owner, "updateAssistantRole");
        }
    }

    public AssistantRole getOrCreateAssistantRole(User owner, String defaultContent) {
        if (owner instanceof TelegramGroup group) {
            return telegramGroupService.getOrCreateAssistantRole(group, defaultContent);
        }
        if (owner instanceof TelegramUser user) {
            return telegramUserService.getOrCreateAssistantRole(user, defaultContent);
        }
        throw unsupported(owner, "getOrCreateAssistantRole");
    }

    public void updateMenuVersionHash(User owner, String hash) {
        if (owner instanceof TelegramGroup group) {
            telegramGroupService.updateMenuVersionHash(group.getTelegramId(), hash);
        } else if (owner instanceof TelegramUser user) {
            telegramUserService.updateMenuVersionHash(user.getTelegramId(), hash);
        } else {
            throw unsupported(owner, "updateMenuVersionHash");
        }
    }

    public String menuVersionHashOf(User owner) {
        if (owner instanceof TelegramGroup group) return group.getMenuVersionHash();
        if (owner instanceof TelegramUser user) return user.getMenuVersionHash();
        throw unsupported(owner, "menuVersionHashOf");
    }

    public void setPreferredModel(User owner, String modelName) {
        if (owner instanceof TelegramGroup group) {
            telegramGroupService.updatePreferredModel(group.getTelegramId(), modelName);
        } else if (owner instanceof TelegramUser user) {
            user.setPreferredModelId(modelName);
            telegramUserService.updateUserActivity(user);
        } else {
            throw unsupported(owner, "setPreferredModel");
        }
    }

    public void clearPreferredModel(User owner) {
        setPreferredModel(owner, null);
    }

    public Optional<String> getPreferredModel(User owner) {
        if (owner == null) return Optional.empty();
        String value = owner.getPreferredModelId();
        return (value != null && !value.isBlank()) ? Optional.of(value) : Optional.empty();
    }

    /**
     * Returns the Telegram {@code chat_id} for the given owner (user's id for private chats,
     * group chat id for groups). Never returns {@code null} for a valid telegram-domain owner.
     */
    public Long telegramIdOf(User owner) {
        if (owner instanceof TelegramGroup group) return group.getTelegramId();
        if (owner instanceof TelegramUser user) return user.getTelegramId();
        throw unsupported(owner, "telegramIdOf");
    }

    private static org.telegram.telegrambots.meta.api.objects.User toTelegramApiUser(TelegramUser user) {
        org.telegram.telegrambots.meta.api.objects.User api = new org.telegram.telegrambots.meta.api.objects.User();
        api.setId(user.getTelegramId());
        api.setUserName(user.getUsername());
        api.setFirstName(user.getFirstName());
        api.setLastName(user.getLastName());
        return api;
    }

    private static IllegalArgumentException unsupported(User owner, String op) {
        return new IllegalArgumentException(
                "Unsupported owner type for " + op + ": " + (owner == null ? "null" : owner.getClass().getName()));
    }
}
