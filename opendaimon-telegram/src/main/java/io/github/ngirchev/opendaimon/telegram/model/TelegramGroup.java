package io.github.ngirchev.opendaimon.telegram.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import io.github.ngirchev.opendaimon.common.model.User;

/**
 * Telegram group or supergroup represented as a single logical participant.
 * All chat-scoped settings (language, preferred model, agent mode, thinking mode,
 * assistant role, recent models) live on this row and are shared by every member.
 * <p>
 * {@code telegramId} stores the Telegram {@code chat_id} (negative for groups).
 * Parallel to {@link TelegramUser#telegramId}; positive/negative value space
 * prevents cross-subtype collisions in practice.
 */
@Entity
@Table(name = "telegram_group")
@DiscriminatorValue("TELEGRAM_GROUP")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class TelegramGroup extends User {

    @Column(name = "telegram_id", unique = true, nullable = false)
    private Long telegramId;

    @Column(name = "title", length = 512)
    private String title;

    /**
     * Telegram chat type as reported by the API: {@code "group"} or {@code "supergroup"}.
     */
    @Column(name = "type", length = 32)
    private String type;

    /**
     * SHA-256 hex of the command set last pushed to Telegram for this group via
     * {@code BotCommandScopeChat}. Null when no chat-scoped menu has ever been set.
     * <p>
     * See {@code TelegramBotMenuService#reconcileMenuIfStale} for the update path.
     */
    @Column(name = "menu_version_hash", length = 64)
    private String menuVersionHash;

    @Override
    public Long getId() {
        return super.getId();
    }
}
