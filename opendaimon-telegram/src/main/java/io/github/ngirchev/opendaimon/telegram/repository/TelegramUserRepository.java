package io.github.ngirchev.opendaimon.telegram.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;

import java.util.Optional;

@Repository
public interface TelegramUserRepository extends JpaRepository<TelegramUser, Long> {
    
    Optional<TelegramUser> findByTelegramId(Long telegramId);
    
    boolean existsByTelegramId(Long telegramId);
} 