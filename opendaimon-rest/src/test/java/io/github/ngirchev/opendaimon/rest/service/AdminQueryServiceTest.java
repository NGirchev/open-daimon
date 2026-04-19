package io.github.ngirchev.opendaimon.rest.service;

import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.model.MessageRole;
import io.github.ngirchev.opendaimon.common.model.OpenDaimonMessage;
import io.github.ngirchev.opendaimon.common.model.RequestType;
import io.github.ngirchev.opendaimon.common.model.ResponseStatus;
import io.github.ngirchev.opendaimon.common.model.ThreadScopeKind;
import io.github.ngirchev.opendaimon.common.repository.OpenDaimonMessageRepository;
import io.github.ngirchev.opendaimon.rest.dto.admin.ConversationSummaryDto;
import io.github.ngirchev.opendaimon.rest.dto.admin.MessageDetailDto;
import io.github.ngirchev.opendaimon.rest.dto.admin.MessageSummaryDto;
import io.github.ngirchev.opendaimon.rest.dto.admin.PageResponseDto;
import io.github.ngirchev.opendaimon.rest.exception.UnauthorizedException;
import io.github.ngirchev.opendaimon.rest.model.RestUser;
import io.github.ngirchev.opendaimon.rest.repository.AdminConversationRepository;
import io.github.ngirchev.opendaimon.rest.repository.AdminUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminQueryServiceTest {

    @Mock
    private AdminConversationRepository adminConversationRepository;
    @Mock
    private AdminUserRepository adminUserRepository;
    @Mock
    private OpenDaimonMessageRepository messageRepository;

    private AdminQueryService service;

    @BeforeEach
    void setUp() {
        service = new AdminQueryService(adminConversationRepository, adminUserRepository, messageRepository);
    }

    @Test
    void shouldMapConversationSummaryWithRestUser() {
        RestUser user = new RestUser();
        user.setId(1L);
        user.setEmail("admin@test.com");
        user.setIsAdmin(true);
        ConversationThread t = thread(10L, user);
        Pageable pageable = PageRequest.of(0, 25);
        Page<ConversationThread> page = new PageImpl<>(List.of(t), pageable, 1);
        when(adminConversationRepository.findAllWithFilters(any(), any(), any(), eq(pageable))).thenReturn(page);

        PageResponseDto<ConversationSummaryDto> result = service.listConversations(null, null, null, pageable);

        assertThat(result.content()).hasSize(1);
        ConversationSummaryDto dto = result.content().get(0);
        assertThat(dto.id()).isEqualTo(10L);
        assertThat(dto.threadKey()).isEqualTo("key-10");
        assertThat(dto.scopeKind()).isEqualTo(ThreadScopeKind.USER.name());
        assertThat(dto.user()).isNotNull();
        assertThat(dto.user().userType()).isEqualTo("REST");
        assertThat(dto.user().emailOrTelegramId()).isEqualTo("admin@test.com");
        assertThat(dto.user().isAdmin()).isTrue();
    }

    @Test
    void shouldThrowWhenConversationMissing() {
        when(adminConversationRepository.findByIdWithUser(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getConversation(99L))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Conversation not found");
    }

    @Test
    void shouldReturnMessagesOrderedByRepository() {
        RestUser user = new RestUser();
        user.setId(1L);
        ConversationThread t = thread(10L, user);
        OpenDaimonMessage m1 = message(1L, 1, MessageRole.USER, "hi", List.of());
        OpenDaimonMessage m2 = message(2L, 2, MessageRole.ASSISTANT, "hello",
                List.of(Map.of("storageKey", "k", "mimeType", "image/png")));
        when(adminConversationRepository.findByIdWithUser(10L)).thenReturn(Optional.of(t));
        when(messageRepository.findByThreadOrderBySequenceNumberAsc(t)).thenReturn(List.of(m1, m2));

        List<MessageSummaryDto> result = service.listMessages(10L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).role()).isEqualTo("USER");
        assertThat(result.get(0).attachmentCount()).isZero();
        assertThat(result.get(1).role()).isEqualTo("ASSISTANT");
        assertThat(result.get(1).attachmentCount()).isEqualTo(1);
    }

    @Test
    void shouldTruncateContentPreview() {
        RestUser user = new RestUser();
        user.setId(1L);
        ConversationThread t = thread(10L, user);
        String longContent = "x".repeat(500);
        OpenDaimonMessage m = message(1L, 1, MessageRole.USER, longContent, List.of());
        when(adminConversationRepository.findByIdWithUser(10L)).thenReturn(Optional.of(t));
        when(messageRepository.findByThreadOrderBySequenceNumberAsc(t)).thenReturn(List.of(m));

        List<MessageSummaryDto> result = service.listMessages(10L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).contentPreview()).hasSize(201);
        assertThat(result.get(0).contentPreview()).endsWith("…");
    }

    @Test
    void shouldMapMessageDetailAttachments() {
        RestUser user = new RestUser();
        user.setId(1L);
        ConversationThread t = thread(10L, user);
        OpenDaimonMessage m = message(5L, 3, MessageRole.USER, "text",
                List.of(Map.of(
                        "storageKey", "key-1",
                        "mimeType", "image/jpeg",
                        "filename", "photo.jpg",
                        "expiresAt", "2030-01-01T00:00:00Z")));
        m.setThread(t);
        m.setUser(user);
        m.setRequestType(RequestType.IMAGE);
        m.setStatus(ResponseStatus.SUCCESS);
        m.setMetadata(Map.of("client_ip", "127.0.0.1"));
        when(messageRepository.findById(5L)).thenReturn(Optional.of(m));

        MessageDetailDto dto = service.getMessage(5L);

        assertThat(dto.id()).isEqualTo(5L);
        assertThat(dto.threadId()).isEqualTo(10L);
        assertThat(dto.attachments()).hasSize(1);
        assertThat(dto.attachments().get(0).storageKey()).isEqualTo("key-1");
        assertThat(dto.attachments().get(0).mimeType()).isEqualTo("image/jpeg");
        assertThat(dto.attachments().get(0).filename()).isEqualTo("photo.jpg");
        assertThat(dto.attachments().get(0).expiresAt()).isNotNull();
        assertThat(dto.metadata()).containsEntry("client_ip", "127.0.0.1");
    }

    private ConversationThread thread(Long id, RestUser user) {
        ConversationThread t = new ConversationThread();
        t.setId(id);
        t.setThreadKey("key-" + id);
        t.setScopeKind(ThreadScopeKind.USER);
        t.setScopeId(user.getId());
        t.setTotalMessages(5);
        t.setTotalTokens(100L);
        t.setIsActive(true);
        t.setLastActivityAt(OffsetDateTime.now());
        t.setUser(user);
        return t;
    }

    private OpenDaimonMessage message(Long id, int seq, MessageRole role, String content,
                                      List<Map<String, Object>> attachments) {
        OpenDaimonMessage m = new OpenDaimonMessage();
        m.setId(id);
        m.setSequenceNumber(seq);
        m.setRole(role);
        m.setContent(content);
        m.setAttachments(attachments.isEmpty() ? null : List.copyOf(attachments));
        m.setCreatedAt(OffsetDateTime.now());
        return m;
    }
}
