package io.github.ngirchev.opendaimon.rest.controller;

import io.github.ngirchev.opendaimon.rest.dto.admin.PageResponseDto;
import io.github.ngirchev.opendaimon.rest.dto.admin.UserSummaryDto;
import io.github.ngirchev.opendaimon.rest.service.AdminQueryService;
import io.github.ngirchev.opendaimon.rest.service.model.AdminPageResponse;
import io.github.ngirchev.opendaimon.rest.service.model.AdminUserSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin user lookup — drives the "owner" filter dropdown in the conversation list view.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Admin User Controller", description = "Admin read-only user list")
public class AdminUserController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AdminQueryService adminQueryService;

    @GetMapping
    @Operation(summary = "List users polymorphically (REST + Telegram + base)")
    public ResponseEntity<PageResponseDto<UserSummaryDto>> listUsers(
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "25") int size) {
        int boundedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int boundedPage = Math.max(page, 0);
        PageRequest pageable = PageRequest.of(boundedPage, boundedSize, Sort.by("id"));
        return ResponseEntity.ok(toDto(adminQueryService.listUsers(search, pageable)));
    }

    private static PageResponseDto<UserSummaryDto> toDto(AdminPageResponse<AdminUserSummary> page) {
        return new PageResponseDto<>(
                page.content().stream().map(AdminUserController::toDto).toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages());
    }

    private static UserSummaryDto toDto(AdminUserSummary user) {
        return new UserSummaryDto(
                user.id(),
                user.userType(),
                user.username(),
                user.firstName(),
                user.lastName(),
                user.emailOrTelegramId(),
                user.isAdmin(),
                user.isBlocked());
    }
}
