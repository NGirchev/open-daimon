package io.github.ngirchev.opendaimon.rest.service.model;

import org.springframework.data.domain.Page;

import java.util.List;

public record AdminPageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static <T> AdminPageResponse<T> from(Page<T> page) {
        return new AdminPageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
