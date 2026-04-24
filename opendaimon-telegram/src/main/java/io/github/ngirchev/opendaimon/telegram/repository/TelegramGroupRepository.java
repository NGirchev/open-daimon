package io.github.ngirchev.opendaimon.telegram.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import io.github.ngirchev.opendaimon.telegram.model.TelegramGroup;

import java.util.Optional;

@Repository
public interface TelegramGroupRepository extends JpaRepository<TelegramGroup, Long> {

    Optional<TelegramGroup> findByTelegramId(Long telegramId);

    boolean existsByTelegramId(Long telegramId);
}
