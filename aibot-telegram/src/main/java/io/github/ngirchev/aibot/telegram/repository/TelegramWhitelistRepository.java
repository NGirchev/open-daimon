package io.github.ngirchev.aibot.telegram.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import io.github.ngirchev.aibot.telegram.model.TelegramUser;
import io.github.ngirchev.aibot.telegram.model.TelegramWhitelist;

import java.util.List;

@Repository
public interface TelegramWhitelistRepository extends JpaRepository<TelegramWhitelist, Long> {
    @Query("SELECT COUNT(w) > 0 FROM TelegramWhitelist w WHERE w.user.id = :userId")
    boolean existsByUserId(@Param("userId") Long userId);

    @Query("SELECT w.user.id FROM TelegramWhitelist w")
    List<Long> findAllUserIds();

    @Query("SELECT w.user FROM TelegramWhitelist w")
    List<TelegramUser> findAllTelegramUsers();
}
