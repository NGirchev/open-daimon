package io.github.ngirchev.opendaimon.rest.service.model;

public record AdminUserSummary(
        Long id,
        String userType,
        String username,
        String firstName,
        String lastName,
        String emailOrTelegramId,
        Boolean isAdmin,
        Boolean isBlocked
) {
}
