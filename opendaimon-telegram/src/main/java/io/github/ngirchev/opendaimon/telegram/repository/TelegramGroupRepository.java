package io.github.ngirchev.opendaimon.telegram.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import io.github.ngirchev.opendaimon.telegram.model.TelegramGroup;

import java.util.Optional;

public interface TelegramGroupRepository extends JpaRepository<TelegramGroup, Long> {

    Optional<TelegramGroup> findByTelegramId(Long telegramId);

    boolean existsByTelegramId(Long telegramId);
}
