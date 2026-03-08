package io.github.ngirchev.aibot.rest.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.github.ngirchev.aibot.common.service.MessageLocalizationService;
import io.github.ngirchev.aibot.rest.exception.UnauthorizedException;
import io.github.ngirchev.aibot.rest.model.RestUser;
import io.github.ngirchev.aibot.rest.repository.RestUserRepository;

import java.util.Optional;

/**
 * Service for authorizing REST API users by email.
 */
@Slf4j
@RequiredArgsConstructor
public class RestAuthorizationService {

    private final RestUserRepository restUserRepository;
    private final MessageLocalizationService messageLocalizationService;

    /**
     * Authorizes user by email using default locale (ru).
     */
    public RestUser authorize(String email) {
        return authorize(email, null);
    }

    /**
     * Authorizes user by email. Messages are localized by languageCode (e.g. from Accept-Language).
     *
     * @param email        user email
     * @param languageCode optional locale (ru, en); null = default ru
     * @return found user
     * @throws UnauthorizedException if email is missing or user not found
     */
    public RestUser authorize(String email, String languageCode) {
        if (email == null || email.isBlank()) {
            log.warn("Access attempt without email");
            throw new UnauthorizedException(messageLocalizationService.getMessage("rest.auth.email.required", languageCode));
        }

        Optional<RestUser> userOpt = restUserRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            log.warn("Access attempt with invalid email: {}", email);
            throw new UnauthorizedException(messageLocalizationService.getMessage("rest.auth.user.not.found", languageCode));
        }

        RestUser user = userOpt.get();
        log.debug("User {} successfully authorized", user.getEmail());
        return user;
    }
}

