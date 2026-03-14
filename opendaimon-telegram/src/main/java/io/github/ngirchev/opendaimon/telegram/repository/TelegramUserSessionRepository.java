package io.github.ngirchev.opendaimon.telegram.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUserSession;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TelegramUserSessionRepository extends JpaRepository<TelegramUserSession, Long> {
    
    Optional<TelegramUserSession> findByTelegramUserAndSessionId(TelegramUser telegramUser, String sessionId);
    
    Optional<TelegramUserSession> findByTelegramUserAndIsActiveTrue(TelegramUser telegramUser);

    @Query("SELECT s FROM TelegramUserSession s WHERE s.isActive = true AND s.updatedAt < :threshold")
    Page<TelegramUserSession> findActiveSessionsBefore(@Param("threshold") OffsetDateTime threshold, Pageable pageable);

    @Query("SELECT s FROM TelegramUserSession s WHERE s.isActive = true AND s.telegramUser.telegramId = :telegramUserId")
    List<TelegramUserSession> findActiveSessionsForUser(@Param("telegramUserId") Long telegramUserId);
} 