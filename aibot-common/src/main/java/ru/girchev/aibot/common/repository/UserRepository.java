package ru.girchev.aibot.common.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.girchev.aibot.common.model.User;

/**
 * Repository for base user table.
 * Supports polymorphic queries for TelegramUser and RestUser.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
}

