package io.github.ngirchev.opendaimon.rest.controller;

import io.github.ngirchev.opendaimon.common.model.ThreadScopeKind;
import io.github.ngirchev.opendaimon.rest.dto.admin.ConversationSummaryDto;
import io.github.ngirchev.opendaimon.rest.dto.admin.MessageDetailDto;
import io.github.ngirchev.opendaimon.rest.dto.admin.MessageSummaryDto;
import io.github.ngirchev.opendaimon.rest.dto.admin.PageResponseDto;
import io.github.ngirchev.opendaimon.rest.service.AdminQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only endpoints for the admin panel: list all conversations, drill into messages,
 * fetch a single message detail. Protected by {@link #ROLE} via Spring Security.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Admin Conversation Controller", description = "Admin read-only access to conversations and messages")
public class AdminConversationController {

    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_PAGE_SIZE = 100;

    private final AdminQueryService adminQueryService;

    @GetMapping("/conversations")
    @Operation(summary = "List all conversations across users", description = "Paginated, filterable")
    public ResponseEntity<PageResponseDto<ConversationSummaryDto>> listConversations(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "scopeKind", required = false) ThreadScopeKind scopeKind,
            @RequestParam(value = "isActive", required = false) Boolean isActive,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "25") int size) {
        int boundedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int boundedPage = Math.max(page, 0);
        PageRequest pageable = PageRequest.of(
                boundedPage,
                boundedSize == 0 ? DEFAULT_PAGE_SIZE : boundedSize,
                Sort.by(Sort.Direction.DESC, "lastActivityAt"));
        return ResponseEntity.ok(adminQueryService.listConversations(userId, scopeKind, isActive, pageable));
    }

    @GetMapping("/conversations/{id}")
    @Operation(summary = "Get conversation metadata")
    public ResponseEntity<ConversationSummaryDto> getConversation(@PathVariable Long id) {
        return ResponseEntity.ok(adminQueryService.getConversation(id));
    }

    @GetMapping("/conversations/{id}/messages")
    @Operation(summary = "List messages of a conversation", description = "Sorted by sequenceNumber asc")
    public ResponseEntity<List<MessageSummaryDto>> listMessages(@PathVariable Long id) {
        return ResponseEntity.ok(adminQueryService.listMessages(id));
    }

    @GetMapping("/messages/{id}")
    @Operation(summary = "Get single message with attachments metadata")
    public ResponseEntity<MessageDetailDto> getMessage(@PathVariable Long id) {
        return ResponseEntity.ok(adminQueryService.getMessage(id));
    }
}
