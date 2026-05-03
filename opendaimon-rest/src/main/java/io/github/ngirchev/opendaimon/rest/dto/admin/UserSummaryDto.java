package io.github.ngirchev.opendaimon.rest.dto.admin;

/**
 * Short user info for admin lists and filter dropdown.
 * userType mirrors the JPA discriminator (TELEGRAM, REST, USER).
 */
public record UserSummaryDto(
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
