package io.github.ngirchev.opendaimon.telegram.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import io.github.ngirchev.opendaimon.common.model.User;

@Entity
@Table(name = "telegram_user")
@DiscriminatorValue("TELEGRAM")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class TelegramUser extends User {
    
    @Column(name = "telegram_id", unique = true, nullable = false)
    private Long telegramId;

    /**
     * SHA-256 hex of the command set last pushed to Telegram for this chat via
     * {@code BotCommandScopeChat}. Null when no chat-scoped menu has ever been set —
     * in that case Telegram falls back to the Default-scope menu maintained at startup.
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