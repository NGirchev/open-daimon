package io.github.ngirchev.aibot.telegram.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import io.github.ngirchev.aibot.common.model.User;

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

    @Override
    public Long getId() {
        return super.getId();
    }
} 