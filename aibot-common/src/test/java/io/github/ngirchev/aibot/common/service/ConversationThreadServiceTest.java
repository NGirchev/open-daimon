package io.github.ngirchev.aibot.common.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import io.github.ngirchev.aibot.common.model.ConversationThread;
import io.github.ngirchev.aibot.common.model.AIBotMessage;
import io.github.ngirchev.aibot.common.model.User;
import io.github.ngirchev.aibot.common.repository.ConversationThreadRepository;
import io.github.ngirchev.aibot.common.repository.AIBotMessageRepository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConversationThreadServiceTest {

    @Mock
    private ConversationThreadRepository threadRepository;

    @Mock
    private AIBotMessageRepository messageRepository;

    private ConversationThreadService threadService;

    @BeforeEach
    void setUp() {
        threadService = new ConversationThreadService(
                threadRepository,
                messageRepository
        );
    }

    @Test
    void whenGetOrCreateThread_andActiveThreadExists_thenReturnExistingThread() {
        // Arrange
        User user = createTestUser();
        ConversationThread existingThread = createThread(user, true, OffsetDateTime.now().minusHours(1));

        when(threadRepository.findMostRecentActiveThread(user))
                .thenReturn(Optional.of(existingThread));

        // Act
        ConversationThread result = threadService.getOrCreateThread(user);

        // Assert
        assertNotNull(result);
        assertEquals(existingThread.getThreadKey(), result.getThreadKey());
        verify(threadRepository, never()).save(any());
    }

    @Test
    void whenGetOrCreateThread_andNoActiveThread_thenCreateNewThread() {
        // Arrange
        User user = createTestUser();

        when(threadRepository.findMostRecentActiveThread(user))
                .thenReturn(Optional.empty());

        ConversationThread newThread = createThread(user, true, OffsetDateTime.now());
        when(threadRepository.save(any(ConversationThread.class))).thenReturn(newThread);

        // Act
        ConversationThread result = threadService.getOrCreateThread(user);

        // Assert
        assertNotNull(result);
        verify(threadRepository).save(any(ConversationThread.class));
    }

    @Test
    void whenGetOrCreateThread_andThreadInactive_thenCreateNewThread() {
        // Arrange
        User user = createTestUser();
        ConversationThread inactiveThread = createThread(user, false, OffsetDateTime.now().minusHours(25));

        when(threadRepository.findMostRecentActiveThread(user))
                .thenReturn(Optional.of(inactiveThread));

        ConversationThread newThread = createThread(user, true, OffsetDateTime.now());
        when(threadRepository.save(any(ConversationThread.class))).thenReturn(newThread);

        // Act
        ConversationThread result = threadService.getOrCreateThread(user);

        // Assert
        assertNotNull(result);
        verify(threadRepository).save(any(ConversationThread.class));
    }

    @Test
    void whenCreateNewThread_thenThreadIsCreated() {
        // Arrange
        User user = createTestUser();
        ConversationThread savedThread = createThread(user, true, OffsetDateTime.now());
        when(threadRepository.save(any(ConversationThread.class))).thenReturn(savedThread);

        // Act
        ConversationThread result = threadService.createNewThread(user);

        // Assert
        assertNotNull(result);
        assertNotNull(result.getThreadKey());
        assertTrue(result.getIsActive());
        assertEquals(0, result.getTotalMessages());
        assertEquals(0L, result.getTotalTokens());
        verify(threadRepository).save(any(ConversationThread.class));
    }

    @Test
    void whenUpdateThreadTitleIfNeeded_andTitleIsNull_thenSetTitle() {
        // Arrange
        User user = createTestUser();
        ConversationThread thread = createThread(user, true, OffsetDateTime.now());
        thread.setTitle(null);
        String firstMessage = "This is the first user message";

        when(threadRepository.save(any(ConversationThread.class))).thenReturn(thread);

        // Act
        threadService.updateThreadTitleIfNeeded(thread, firstMessage);

        // Assert
        assertNotNull(thread.getTitle());
        assertTrue(thread.getTitle().length() <= 50);
        verify(threadRepository).save(thread);
    }

    @Test
    void whenUpdateThreadTitleIfNeeded_andTitleExists_thenDoNothing() {
        // Arrange
        User user = createTestUser();
        ConversationThread thread = createThread(user, true, OffsetDateTime.now());
        thread.setTitle("Existing Title");
        String firstMessage = "This is the first user message";

        // Act
        threadService.updateThreadTitleIfNeeded(thread, firstMessage);

        // Assert
        assertEquals("Existing Title", thread.getTitle());
        verify(threadRepository, never()).save(any());
    }

    @Test
    void whenUpdateThreadTitleIfNeeded_andMessageTooLong_thenTruncateTitle() {
        // Arrange
        User user = createTestUser();
        ConversationThread thread = createThread(user, true, OffsetDateTime.now());
        thread.setTitle(null);
        String longMessage = "This is a very long user message that should be truncated to 50 characters";

        when(threadRepository.save(any(ConversationThread.class))).thenReturn(thread);

        // Act
        threadService.updateThreadTitleIfNeeded(thread, longMessage);

        // Assert
        assertNotNull(thread.getTitle());
        assertTrue(thread.getTitle().length() <= 50);
        assertTrue(thread.getTitle().endsWith("..."));
        verify(threadRepository).save(thread);
    }

    @Test
    void whenUpdateThreadCounters_thenCountersAreUpdated() {
        // Arrange
        User user = createTestUser();
        ConversationThread thread = createThread(user, true, OffsetDateTime.now());

        AIBotMessage message1 = createMessage(thread, 1, 100);
        AIBotMessage message2 = createMessage(thread, 2, 200);
        AIBotMessage message3 = createMessage(thread, 3, 150);
        AIBotMessage message4 = createMessage(thread, 4, 250);

        when(messageRepository.countByThread(thread)).thenReturn(4);
        when(messageRepository.findByThreadOrderBySequenceNumberAsc(thread))
                .thenReturn(List.of(message1, message2, message3, message4));
        when(threadRepository.save(any(ConversationThread.class))).thenReturn(thread);

        // Act
        threadService.updateThreadCounters(thread);

        // Assert
        assertEquals(4, thread.getTotalMessages());
        assertEquals(700L, thread.getTotalTokens()); // 100 + 200 + 150 + 250
        assertNotNull(thread.getLastActivityAt());
        verify(threadRepository).save(thread);
    }

    @Test
    void whenUpdateThreadCounters_andNoMessages_thenCountersAreZero() {
        // Arrange
        User user = createTestUser();
        ConversationThread thread = createThread(user, true, OffsetDateTime.now());

        when(messageRepository.countByThread(thread)).thenReturn(0);
        when(messageRepository.findByThreadOrderBySequenceNumberAsc(thread))
                .thenReturn(List.of());
        when(threadRepository.save(any(ConversationThread.class))).thenReturn(thread);

        // Act
        threadService.updateThreadCounters(thread);

        // Assert
        assertEquals(0, thread.getTotalMessages());
        assertEquals(0L, thread.getTotalTokens());
        verify(threadRepository).save(thread);
    }

    @Test
    void whenCloseThread_thenThreadIsClosed() {
        // Arrange
        User user = createTestUser();
        ConversationThread thread = createThread(user, true, OffsetDateTime.now());
        when(threadRepository.save(any(ConversationThread.class))).thenReturn(thread);

        // Act
        threadService.closeThread(thread);

        // Assert
        assertFalse(thread.getIsActive());
        assertNotNull(thread.getClosedAt());
        verify(threadRepository).save(thread);
    }

    @Test
    void whenUpdateThreadSummary_thenSummaryIsUpdated() {
        // Arrange
        User user = createTestUser();
        ConversationThread thread = createThread(user, true, OffsetDateTime.now());
        String summary = "Short dialogue summary";
        List<String> memoryBullets = List.of("Fact 1", "Fact 2", "Fact 3");

        when(threadRepository.save(any(ConversationThread.class))).thenReturn(thread);

        // Act
        threadService.updateThreadSummary(thread, summary, memoryBullets);

        // Assert
        assertEquals(summary, thread.getSummary());
        assertEquals(3, thread.getMemoryBullets().size());
        verify(threadRepository).save(thread);
    }

    @Test
    void whenUpdateThreadSummary_withNullBullets_thenEmptyListIsSet() {
        // Arrange
        User user = createTestUser();
        ConversationThread thread = createThread(user, true, OffsetDateTime.now());
        String summary = "Short dialogue summary";

        when(threadRepository.save(any(ConversationThread.class))).thenReturn(thread);

        // Act
        threadService.updateThreadSummary(thread, summary, null);

        // Assert
        assertEquals(summary, thread.getSummary());
        assertNotNull(thread.getMemoryBullets());
        assertTrue(thread.getMemoryBullets().isEmpty());
        verify(threadRepository).save(thread);
    }

    @Test
    void whenFindByThreadKey_thenReturnThread() {
        // Arrange
        User user = createTestUser();
        ConversationThread thread = createThread(user, true, OffsetDateTime.now());
        String threadKey = thread.getThreadKey();

        when(threadRepository.findByThreadKey(threadKey)).thenReturn(Optional.of(thread));

        // Act
        Optional<ConversationThread> result = threadService.findByThreadKey(threadKey);

        // Assert
        assertTrue(result.isPresent());
        assertEquals(threadKey, result.get().getThreadKey());
        verify(threadRepository).findByThreadKey(threadKey);
    }

    // Helper methods
    private User createTestUser() {
        User user = new User() {};
        user.setId(1L);
        user.setUsername("testuser");
        user.setFirstName("Test");
        user.setLastName("User");
        user.setCreatedAt(OffsetDateTime.now());
        user.setUpdatedAt(OffsetDateTime.now());
        user.setLastActivityAt(OffsetDateTime.now());
        return user;
    }

    private ConversationThread createThread(User user, boolean isActive, OffsetDateTime lastActivity) {
        ConversationThread thread = new ConversationThread();
        thread.setId(1L);
        thread.setUser(user);
        thread.setThreadKey(UUID.randomUUID().toString());
        thread.setIsActive(isActive);
        thread.setLastActivityAt(lastActivity);
        thread.setTotalMessages(0);
        thread.setTotalTokens(0L);
        thread.setMemoryBullets(new ArrayList<>());
        return thread;
    }

    private AIBotMessage createMessage(ConversationThread thread, int sequenceNumber, int tokenCount) {
        AIBotMessage message = new AIBotMessage();
        message.setId((long) sequenceNumber);
        message.setThread(thread);
        message.setSequenceNumber(sequenceNumber);
        message.setTokenCount(tokenCount);
        message.setRole(sequenceNumber % 2 == 1 ? io.github.ngirchev.aibot.common.model.MessageRole.USER : io.github.ngirchev.aibot.common.model.MessageRole.ASSISTANT);
        message.setContent("Test message " + sequenceNumber);
        return message;
    }
}

