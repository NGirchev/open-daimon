package io.github.ngirchev.opendaimon.common.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import io.github.ngirchev.opendaimon.common.model.User;

/**
 * Repository for base user table.
 * Supports polymorphic queries for TelegramUser and RestUser.
 */
public interface UserRepository extends JpaRepository<User, Long> {
}

