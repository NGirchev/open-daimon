package io.github.ngirchev.aibot.common.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.github.ngirchev.aibot.bulkhead.service.IUserObject;
import io.github.ngirchev.aibot.bulkhead.service.IUserService;
import io.github.ngirchev.aibot.common.repository.UserRepository;

import java.util.Optional;

/**
 * Generic service for user operations.
 * Finds users by id from base user table.
 * Supports both TelegramUser and RestUser via JPA polymorphic queries.
 */
@Slf4j
@RequiredArgsConstructor
public class UserService implements IUserService {

    private final UserRepository userRepository;

    @Override
    public Optional<? extends IUserObject> findById(Long userId) {
        return userRepository.findById(userId)
                .map(IUserObject.class::cast);
    }
}
