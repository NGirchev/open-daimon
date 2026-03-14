package io.github.ngirchev.aibot.telegram.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import io.github.ngirchev.aibot.common.config.CoreJpaConfig;
import io.github.ngirchev.aibot.common.config.CoreFlywayConfig;
import io.github.ngirchev.aibot.common.model.ConversationThread;
import io.github.ngirchev.aibot.common.model.AIBotMessage;
import io.github.ngirchev.aibot.common.model.MessageRole;
import io.github.ngirchev.aibot.common.model.ResponseStatus;
import io.github.ngirchev.aibot.common.repository.AIBotMessageRepository;
import io.github.ngirchev.aibot.common.repository.ConversationThreadRepository;
import io.github.ngirchev.aibot.telegram.config.TelegramJpaConfig;
import io.github.ngirchev.aibot.telegram.config.TelegramFlywayConfig;
import io.github.ngirchev.aibot.telegram.model.TelegramUser;
import io.github.ngirchev.aibot.telegram.model.TelegramUserSession;
import io.github.ngirchev.aibot.test.TestDatabaseConfiguration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        TestDatabaseConfiguration.class,
        CoreJpaConfig.class,
        TelegramJpaConfig.class,
        CoreFlywayConfig.class,
        TelegramFlywayConfig.class
})
class TelegramAIBotMessageRepositoryIT {

    @Autowired
    private AIBotMessageRepository messageRepository;

    @Autowired
    private TelegramUserRepository telegramUserRepository;

    @Autowired
    private TelegramUserSessionRepository telegramUserSessionRepository;

    @Autowired
    private ConversationThreadRepository threadRepository;

    @Test
    void whenFindByThreadOrderBySequenceNumberAsc_thenReturnMessagesInSequenceOrder() {
        // Arrange
        TelegramUser user = createTestUser();
        telegramUserRepository.save(user);

        TelegramUserSession session = createSession(user);
        telegramUserSessionRepository.save(session);

        ConversationThread thread = createThread(user);
        threadRepository.save(thread);

        AIBotMessage userMessage1 = createUserMessage(user, thread, 1, "Request 1");
        AIBotMessage assistantMessage1 = createAssistantMessage(user, thread, 2, "Response 1");
        AIBotMessage userMessage2 = createUserMessage(user, thread, 3, "Request 2");
        AIBotMessage assistantMessage2 = createAssistantMessage(user, thread, 4, "Response 2");
        messageRepository.saveAll(List.of(userMessage1, assistantMessage1, userMessage2, assistantMessage2));

        // Act - get ASSISTANT messages only
        List<AIBotMessage> messages = messageRepository.findByThreadOrderBySequenceNumberAsc(thread)
                .stream()
                .filter(m -> m.getRole() == MessageRole.ASSISTANT)
                .toList();

        // Assert
        assertEquals(2, messages.size());
        assertEquals("Response 1", messages.get(0).getContent());
        assertEquals(2, messages.get(0).getSequenceNumber());
        assertEquals("Response 2", messages.get(1).getContent());
        assertEquals(4, messages.get(1).getSequenceNumber());
    }

    @Test
    void whenFindByThreadAndRole_thenReturnOnlyAssistantMessages() {
        // Arrange
        TelegramUser user = createTestUser();
        telegramUserRepository.save(user);

        TelegramUserSession session = createSession(user);
        telegramUserSessionRepository.save(session);

        ConversationThread thread = createThread(user);
        threadRepository.save(thread);

        AIBotMessage userMessage = createUserMessage(user, thread, 1, "Test request");
        AIBotMessage assistantMessage = createAssistantMessage(user, thread, 2, "Test response");
        messageRepository.saveAll(List.of(userMessage, assistantMessage));

        // Act
        List<AIBotMessage> assistantMessages = messageRepository.findByThreadAndRoleOrderBySequenceNumberAsc(thread, MessageRole.ASSISTANT);

        // Assert
        assertEquals(1, assistantMessages.size());
        assertEquals("Test response", assistantMessages.get(0).getContent());
        assertEquals(MessageRole.ASSISTANT, assistantMessages.get(0).getRole());
    }

    @Test
    void whenFindByThreadAndRole_andNoMessages_thenReturnEmpty() {
        // Arrange
        TelegramUser user = createTestUser();
        telegramUserRepository.save(user);

        ConversationThread thread = createThread(user);
        threadRepository.save(thread);

        // Act
        List<AIBotMessage> messages = messageRepository.findByThreadAndRoleOrderBySequenceNumberAsc(thread, MessageRole.ASSISTANT);

        // Assert
        assertTrue(messages.isEmpty());
    }

    @Test
    void whenCountByThread_thenReturnCorrectCount() {
        // Arrange
        TelegramUser user = createTestUser();
        telegramUserRepository.save(user);

        TelegramUserSession session = createSession(user);
        telegramUserSessionRepository.save(session);

        ConversationThread thread1 = createThread(user);
        ConversationThread thread2 = createThread(user);
        threadRepository.saveAll(List.of(thread1, thread2));

        AIBotMessage userMsg1 = createUserMessage(user, thread1, 1, "Request 1");
        AIBotMessage assistantMsg1 = createAssistantMessage(user, thread1, 2, "Response 1");
        AIBotMessage userMsg2 = createUserMessage(user, thread1, 3, "Request 2");
        AIBotMessage assistantMsg2 = createAssistantMessage(user, thread1, 4, "Response 2");
        AIBotMessage userMsg3 = createUserMessage(user, thread2, 1, "Request 3");
        AIBotMessage assistantMsg3 = createAssistantMessage(user, thread2, 2, "Response 3");
        messageRepository.saveAll(List.of(userMsg1, assistantMsg1, userMsg2, assistantMsg2, userMsg3, assistantMsg3));

        // Act
        Integer count1 = messageRepository.countByThread(thread1);
        Integer count2 = messageRepository.countByThread(thread2);

        // Assert
        assertEquals(4, count1); // 2 user + 2 assistant
        assertEquals(2, count2); // 1 user + 1 assistant
    }

    @Test
    void whenCountByThreadAndRole_thenReturnCorrectCount() {
        // Arrange
        TelegramUser user = createTestUser();
        telegramUserRepository.save(user);

        TelegramUserSession session = createSession(user);
        telegramUserSessionRepository.save(session);

        ConversationThread thread = createThread(user);
        threadRepository.save(thread);

        AIBotMessage userMsg1 = createUserMessage(user, thread, 1, "Request 1");
        AIBotMessage assistantMsg1 = createAssistantMessage(user, thread, 2, "Response 1");
        AIBotMessage userMsg2 = createUserMessage(user, thread, 3, "Request 2");
        AIBotMessage assistantMsg2 = createAssistantMessage(user, thread, 4, "Response 2");
        messageRepository.saveAll(List.of(userMsg1, assistantMsg1, userMsg2, assistantMsg2));

        // Act
        Integer userCount = messageRepository.countByThreadAndRole(thread, MessageRole.USER);
        Integer assistantCount = messageRepository.countByThreadAndRole(thread, MessageRole.ASSISTANT);

        // Assert
        assertEquals(2, userCount);
        assertEquals(2, assistantCount);
    }

    @Test
    void whenFindByThreadOrderBySequenceNumberAsc_andNoMessages_thenReturnEmptyList() {
        // Arrange
        TelegramUser user = createTestUser();
        telegramUserRepository.save(user);

        ConversationThread thread = createThread(user);
        threadRepository.save(thread);

        // Act
        List<AIBotMessage> messages = messageRepository.findByThreadOrderBySequenceNumberAsc(thread);

        // Assert
        assertTrue(messages.isEmpty());
    }

    @Test
    void whenFindLastByThread_thenReturnLastMessage() {
        // Arrange
        TelegramUser user = createTestUser();
        telegramUserRepository.save(user);

        TelegramUserSession session = createSession(user);
        telegramUserSessionRepository.save(session);

        ConversationThread thread = createThread(user);
        threadRepository.save(thread);

        AIBotMessage userMsg1 = createUserMessage(user, thread, 1, "First request");
        AIBotMessage assistantMsg1 = createAssistantMessage(user, thread, 2, "First response");
        AIBotMessage userMsg2 = createUserMessage(user, thread, 3, "Second request");
        AIBotMessage assistantMsg2 = createAssistantMessage(user, thread, 4, "Second response");
        messageRepository.saveAll(List.of(userMsg1, assistantMsg1, userMsg2, assistantMsg2));

        // Act
        Optional<AIBotMessage> lastMessage = messageRepository.findLastByThread(thread);

        // Assert
        assertTrue(lastMessage.isPresent());
        assertEquals("Second response", lastMessage.get().getContent());
        assertEquals(4, lastMessage.get().getSequenceNumber());
    }

    // Helper methods
    private TelegramUser createTestUser() {
        TelegramUser user = new TelegramUser();
        user.setTelegramId(123L);
        user.setUsername("testuser");
        user.setFirstName("Test");
        user.setLastName("User");
        user.setCreatedAt(OffsetDateTime.now());
        user.setUpdatedAt(OffsetDateTime.now());
        user.setLastActivityAt(OffsetDateTime.now());
        user.setIsAdmin(false);
        user.setIsPremium(false);
        user.setIsBlocked(false);
        return user;
    }

    private TelegramUserSession createSession(TelegramUser user) {
        TelegramUserSession session = new TelegramUserSession();
        session.setTelegramUser(user);
        session.setSessionId(UUID.randomUUID().toString());
        session.setCreatedAt(OffsetDateTime.now());
        session.setUpdatedAt(OffsetDateTime.now());
        return session;
    }

    private ConversationThread createThread(TelegramUser user) {
        ConversationThread thread = new ConversationThread();
        thread.setUser(user);
        thread.setThreadKey(UUID.randomUUID().toString());
        thread.setIsActive(true);
        thread.setLastActivityAt(OffsetDateTime.now());
        thread.setTotalMessages(0);
        thread.setTotalTokens(0L);
        thread.setMemoryBullets(List.of());
        return thread;
    }

    private AIBotMessage createUserMessage(TelegramUser user, ConversationThread thread, int sequenceNumber, String content) {
        AIBotMessage message = new AIBotMessage();
        message.setUser(user);
        message.setRole(MessageRole.USER);
        message.setContent(content);
        message.setThread(thread);
        message.setSequenceNumber(sequenceNumber);
        return message;
    }

    private AIBotMessage createAssistantMessage(TelegramUser user, ConversationThread thread,
                                                int sequenceNumber, String content) {
        AIBotMessage message = new AIBotMessage();
        message.setUser(user);
        message.setRole(MessageRole.ASSISTANT);
        message.setContent(content);
        message.setServiceName("testService");
        message.setStatus(ResponseStatus.SUCCESS);
        message.setProcessingTimeMs(100);
        message.setThread(thread);
        message.setSequenceNumber(sequenceNumber);
        return message;
    }
}
