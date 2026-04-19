package io.github.ngirchev.opendaimon.rest.dto.admin;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Serialization-stable pagination envelope.
 * Spring Data's own {@code Page} JSON shape is unstable across versions;
 * this record fixes the contract for the admin UI.
 */
public record PageResponseDto<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static <T> PageResponseDto<T> from(Page<T> page) {
        return new PageResponseDto<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
