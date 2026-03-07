package ru.girchev.aibot.rest.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.girchev.aibot.rest.model.RestUser;
import ru.girchev.aibot.rest.repository.RestUserRepository;

import java.util.Optional;

/**
 * Сервис для авторизации REST API пользователей по email
 */
@Slf4j
@RequiredArgsConstructor
public class RestAuthorizationService {
    
    private final RestUserRepository restUserRepository;
    
    /**
     * Авторизует пользователя по email
     * 
     * @param email Email пользователя
     * @return найденный пользователь
     * @throws ru.girchev.aibot.rest.exception.UnauthorizedException если email не указан или пользователь не найден
     */
    public RestUser authorize(String email) {
        if (email == null || email.isBlank()) {
            log.warn("Access attempt without email");
            throw new ru.girchev.aibot.rest.exception.UnauthorizedException("Email обязателен для доступа");
        }
        
        Optional<RestUser> userOpt = restUserRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            log.warn("Access attempt with invalid email: {}", email);
            throw new ru.girchev.aibot.rest.exception.UnauthorizedException("Пользователь с указанным email не найден");
        }
        
        RestUser user = userOpt.get();
        log.debug("User {} successfully authorized", user.getEmail());
        return user;
    }
}

