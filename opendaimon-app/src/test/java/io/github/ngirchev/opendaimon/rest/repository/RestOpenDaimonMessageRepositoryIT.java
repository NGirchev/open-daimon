package io.github.ngirchev.opendaimon.rest.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import io.github.ngirchev.opendaimon.common.config.CoreJpaConfig;
import io.github.ngirchev.opendaimon.common.config.CoreFlywayConfig;
import io.github.ngirchev.opendaimon.rest.config.RestJpaConfig;
import io.github.ngirchev.opendaimon.rest.config.RestFlywayConfig;
import io.github.ngirchev.opendaimon.common.model.AssistantRole;
import io.github.ngirchev.opendaimon.common.model.OpenDaimonMessage;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.model.MessageRole;
import io.github.ngirchev.opendaimon.common.model.RequestType;
import io.github.ngirchev.opendaimon.common.model.ResponseStatus;
import io.github.ngirchev.opendaimon.common.repository.AssistantRoleRepository;
import io.github.ngirchev.opendaimon.common.repository.OpenDaimonMessageRepository;
import io.github.ngirchev.opendaimon.common.repository.ConversationThreadRepository;
import io.github.ngirchev.opendaimon.rest.model.RestUser;
import io.github.ngirchev.opendaimon.test.TestDatabaseConfiguration;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        TestDatabaseConfiguration.class,
        CoreJpaConfig.class,
        RestJpaConfig.class,
        CoreFlywayConfig.class,
        RestFlywayConfig.class
})
@TestPropertySource(properties = {
        "open-daimon.rest.enabled=true"
})
class RestOpenDaimonMessageRepositoryIT {

    @Autowired
    private OpenDaimonMessageRepository messageRepository;
    
    @Autowired
    private RestUserRepository userRepository;
    
    @Autowired
    private AssistantRoleRepository assistantRoleRepository;
    
    @Autowired
    private ConversationThreadRepository threadRepository;

    @Test
    void whenSaveMessage_thenMessageIsSaved() {
        // Arrange
        RestUser user = new RestUser();
        user.setEmail("test@example.com");
        user.setUsername("testuser");
        user.setFirstName("Test");
        user.setLastName("User");
        user.setCreatedAt(OffsetDateTime.now());
        user.setUpdatedAt(OffsetDateTime.now());
        user.setLastActivityAt(OffsetDateTime.now());
        user.setIsAdmin(false);
        user.setIsPremium(false);
        user.setIsBlocked(false);
        userRepository.save(user);

        AssistantRole assistantRole = new AssistantRole();
        assistantRole.setUser(user);
        assistantRole.setContent("assistant");
        assistantRole.setContentHash(String.valueOf("assistant".hashCode()));
        assistantRole.setVersion(1);
        assistantRole.setIsActive(true);
        assistantRole.setCreatedAt(OffsetDateTime.now());
        assistantRole.setUsageCount(0L);
        assistantRoleRepository.save(assistantRole);

        OpenDaimonMessage message = new OpenDaimonMessage();
        message.setUser(user);
        message.setRole(MessageRole.USER);
        message.setContent("Test request");
        message.setRequestType(RequestType.TEXT);
        message.setAssistantRole(assistantRole);
        
        // Fill metadata for REST
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("client_ip", "127.0.0.1");
        metadata.put("user_agent", "Mozilla/5.0");
        metadata.put("endpoint", "/api/chat");
        message.setMetadata(metadata);

        // Act
        OpenDaimonMessage saved = messageRepository.save(message);

        // Assert
        assertNotNull(saved.getId());
        assertEquals("Test request", saved.getContent());
        assertEquals(MessageRole.USER, saved.getRole());
        assertEquals(RequestType.TEXT, saved.getRequestType());
        assertNotNull(saved.getAssistantRole());
        assertEquals("assistant", saved.getAssistantRole().getContent());
        assertEquals(user.getId(), saved.getUser().getId());
        
        // Verify metadata
        assertNotNull(saved.getMetadata());
        assertEquals("127.0.0.1", saved.getMetadata().get("client_ip"));
        assertEquals("Mozilla/5.0", saved.getMetadata().get("user_agent"));
        assertEquals("/api/chat", saved.getMetadata().get("endpoint"));
    }

    @Test
    void whenFindAll_thenReturnAllMessages() {
        // Arrange
        RestUser user = new RestUser();
        user.setEmail("test@example.com");
        user.setUsername("testuser");
        user.setFirstName("Test");
        user.setLastName("User");
        user.setCreatedAt(OffsetDateTime.now());
        user.setUpdatedAt(OffsetDateTime.now());
        user.setLastActivityAt(OffsetDateTime.now());
        user.setIsAdmin(false);
        user.setIsPremium(false);
        user.setIsBlocked(false);
        userRepository.save(user);

        OpenDaimonMessage message1 = createRestMessage(user, "First request", "127.0.0.1", "Mozilla/5.0", "/api/chat");
        OpenDaimonMessage message2 = createRestMessage(user, "Second request", "127.0.0.1", "Mozilla/5.0", "/api/chat");
        messageRepository.saveAll(List.of(message1, message2));

        // Act
        List<OpenDaimonMessage> messages = messageRepository.findAll();

        // Assert
        assertTrue(messages.size() >= 2);
        assertTrue(messages.stream().anyMatch(m -> m.getContent().equals("First request")));
        assertTrue(messages.stream().anyMatch(m -> m.getContent().equals("Second request")));
    }

    @Test
    void whenFindById_thenReturnMessage() {
        // Arrange
        RestUser user = new RestUser();
        user.setEmail("test@example.com");
        user.setUsername("testuser");
        user.setFirstName("Test");
        user.setLastName("User");
        user.setCreatedAt(OffsetDateTime.now());
        user.setUpdatedAt(OffsetDateTime.now());
        user.setLastActivityAt(OffsetDateTime.now());
        user.setIsAdmin(false);
        user.setIsPremium(false);
        user.setIsBlocked(false);
        userRepository.save(user);

        OpenDaimonMessage message = createRestMessage(user, "Test request", "127.0.0.1", "Mozilla/5.0", "/api/chat");
        OpenDaimonMessage saved = messageRepository.save(message);

        // Act
        OpenDaimonMessage found = messageRepository.findById(saved.getId()).orElse(null);

        // Assert
        assertNotNull(found);
        assertEquals(saved.getId(), found.getId());
        assertEquals("Test request", found.getContent());
        assertEquals(MessageRole.USER, found.getRole());
        assertEquals(RequestType.TEXT, found.getRequestType());
        assertEquals("127.0.0.1", found.getMetadata().get("client_ip"));
        assertEquals("Mozilla/5.0", found.getMetadata().get("user_agent"));
        assertEquals("/api/chat", found.getMetadata().get("endpoint"));
        assertEquals(user.getId(), found.getUser().getId());
    }

    @Test
    void whenDeleteMessage_thenMessageIsDeleted() {
        // Arrange
        RestUser user = new RestUser();
        user.setEmail("test@example.com");
        user.setUsername("testuser");
        user.setFirstName("Test");
        user.setLastName("User");
        user.setCreatedAt(OffsetDateTime.now());
        user.setUpdatedAt(OffsetDateTime.now());
        user.setLastActivityAt(OffsetDateTime.now());
        user.setIsAdmin(false);
        user.setIsPremium(false);
        user.setIsBlocked(false);
        userRepository.save(user);

        OpenDaimonMessage message = createRestMessage(user, "Test request", "127.0.0.1", "Mozilla/5.0", "/api/chat");
        OpenDaimonMessage saved = messageRepository.save(message);

        // Act
        messageRepository.delete(saved);

        // Assert
        assertFalse(messageRepository.existsById(saved.getId()));
    }

    @Test
    void whenFindByThreadOrderBySequenceNumberAsc_thenReturnMessagesInSequenceOrder() {
        // Arrange
        RestUser user = createTestUser();
        userRepository.save(user);

        ConversationThread thread = createThread(user);
        threadRepository.save(thread);

        OpenDaimonMessage userMessage1 = createUserMessage(user, thread, 1, "Request 1");
        OpenDaimonMessage assistantMessage1 = createAssistantMessage(user, thread, 2, "Response 1");
        OpenDaimonMessage userMessage2 = createUserMessage(user, thread, 3, "Request 2");
        OpenDaimonMessage assistantMessage2 = createAssistantMessage(user, thread, 4, "Response 2");
        messageRepository.saveAll(List.of(userMessage1, assistantMessage1, userMessage2, assistantMessage2));

        // Act - get ASSISTANT messages only
        List<OpenDaimonMessage> messages = messageRepository.findByThreadOrderBySequenceNumberAsc(thread)
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
        RestUser user = createTestUser();
        userRepository.save(user);

        ConversationThread thread = createThread(user);
        threadRepository.save(thread);

        OpenDaimonMessage userMessage = createUserMessage(user, thread, 1, "Test request");
        OpenDaimonMessage assistantMessage = createAssistantMessage(user, thread, 2, "Test response");
        messageRepository.saveAll(List.of(userMessage, assistantMessage));

        // Act
        List<OpenDaimonMessage> assistantMessages = messageRepository.findByThreadAndRoleOrderBySequenceNumberAsc(thread, MessageRole.ASSISTANT);

        // Assert
        assertEquals(1, assistantMessages.size());
        assertEquals("Test response", assistantMessages.get(0).getContent());
        assertEquals(MessageRole.ASSISTANT, assistantMessages.get(0).getRole());
    }

    @Test
    void whenFindByThreadAndRole_andNoMessages_thenReturnEmpty() {
        // Arrange
        RestUser user = createTestUser();
        userRepository.save(user);

        ConversationThread thread = createThread(user);
        threadRepository.save(thread);

        // Act
        List<OpenDaimonMessage> messages = messageRepository.findByThreadAndRoleOrderBySequenceNumberAsc(thread, MessageRole.ASSISTANT);

        // Assert
        assertTrue(messages.isEmpty());
    }

    @Test
    void whenCountByThread_thenReturnCorrectCount() {
        // Arrange
        RestUser user = createTestUser();
        userRepository.save(user);

        ConversationThread thread1 = createThread(user);
        ConversationThread thread2 = createThread(user);
        threadRepository.saveAll(List.of(thread1, thread2));

        OpenDaimonMessage userMsg1 = createUserMessage(user, thread1, 1, "Request 1");
        OpenDaimonMessage assistantMsg1 = createAssistantMessage(user, thread1, 2, "Response 1");
        OpenDaimonMessage userMsg2 = createUserMessage(user, thread1, 3, "Request 2");
        OpenDaimonMessage assistantMsg2 = createAssistantMessage(user, thread1, 4, "Response 2");
        OpenDaimonMessage userMsg3 = createUserMessage(user, thread2, 1, "Request 3");
        OpenDaimonMessage assistantMsg3 = createAssistantMessage(user, thread2, 2, "Response 3");
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
        RestUser user = createTestUser();
        userRepository.save(user);

        ConversationThread thread = createThread(user);
        threadRepository.save(thread);

        OpenDaimonMessage userMsg1 = createUserMessage(user, thread, 1, "Request 1");
        OpenDaimonMessage assistantMsg1 = createAssistantMessage(user, thread, 2, "Response 1");
        OpenDaimonMessage userMsg2 = createUserMessage(user, thread, 3, "Request 2");
        OpenDaimonMessage assistantMsg2 = createAssistantMessage(user, thread, 4, "Response 2");
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
        RestUser user = createTestUser();
        userRepository.save(user);

        ConversationThread thread = createThread(user);
        threadRepository.save(thread);

        // Act
        List<OpenDaimonMessage> messages = messageRepository.findByThreadOrderBySequenceNumberAsc(thread);

        // Assert
        assertTrue(messages.isEmpty());
    }

    @Test
    void whenFindLastByThread_thenReturnLastMessage() {
        // Arrange
        RestUser user = createTestUser();
        userRepository.save(user);

        ConversationThread thread = createThread(user);
        threadRepository.save(thread);

        OpenDaimonMessage userMsg1 = createUserMessage(user, thread, 1, "First request");
        OpenDaimonMessage assistantMsg1 = createAssistantMessage(user, thread, 2, "First response");
        OpenDaimonMessage userMsg2 = createUserMessage(user, thread, 3, "Second request");
        OpenDaimonMessage assistantMsg2 = createAssistantMessage(user, thread, 4, "Second response");
        messageRepository.saveAll(List.of(userMsg1, assistantMsg1, userMsg2, assistantMsg2));

        // Act
        Optional<OpenDaimonMessage> lastMessage = messageRepository.findLastByThread(thread);

        // Assert
        assertTrue(lastMessage.isPresent());
        assertEquals("Second response", lastMessage.get().getContent());
        assertEquals(4, lastMessage.get().getSequenceNumber());
    }

    // Helper methods
    private RestUser createTestUser() {
        RestUser user = new RestUser();
        user.setEmail("test@example.com");
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

    private ConversationThread createThread(RestUser user) {
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

    private OpenDaimonMessage createUserMessage(RestUser user, ConversationThread thread, int sequenceNumber, String content) {
        OpenDaimonMessage message = new OpenDaimonMessage();
        message.setUser(user);
        message.setRole(MessageRole.USER);
        message.setContent(content);
        message.setThread(thread);
        message.setSequenceNumber(sequenceNumber);
        return message;
    }

    private OpenDaimonMessage createAssistantMessage(RestUser user, ConversationThread thread,
                                                int sequenceNumber, String content) {
        OpenDaimonMessage message = new OpenDaimonMessage();
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

    private OpenDaimonMessage createRestMessage(RestUser user, String content, String clientIp, String userAgent, String endpoint) {
        OpenDaimonMessage message = new OpenDaimonMessage();
        message.setUser(user);
        message.setRole(MessageRole.USER);
        message.setContent(content);
        message.setRequestType(RequestType.TEXT);
        
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("client_ip", clientIp);
        metadata.put("user_agent", userAgent);
        metadata.put("endpoint", endpoint);
        message.setMetadata(metadata);
        
        return message;
    }
}
