package io.github.ngirchev.opendaimon.it.rest.repository;

import io.github.ngirchev.opendaimon.common.config.CoreFlywayConfig;
import io.github.ngirchev.opendaimon.common.config.CoreJpaConfig;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.repository.ConversationThreadRepository;
import io.github.ngirchev.opendaimon.rest.config.RestFlywayConfig;
import io.github.ngirchev.opendaimon.rest.config.RestJpaConfig;
import io.github.ngirchev.opendaimon.rest.model.RestUser;
import io.github.ngirchev.opendaimon.rest.repository.RestUserRepository;
import io.github.ngirchev.opendaimon.test.TestDatabaseConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
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
class RestConversationThreadRepositoryIT {

    @Autowired
    private ConversationThreadRepository threadRepository;

    @Autowired
    private RestUserRepository restUserRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @Transactional
    void whenFindByThreadKey_thenReturnThread() {
        // Arrange
        RestUser user = createTestUser();
        restUserRepository.save(user);

        ConversationThread thread = new ConversationThread();
        thread.setUser(user);
        thread.setThreadKey(UUID.randomUUID().toString());
        thread.setIsActive(true);
        thread.setLastActivityAt(OffsetDateTime.now());
        thread.setTotalMessages(0);
        thread.setTotalTokens(0L);
        thread.setMemoryBullets(List.of());
        threadRepository.save(thread);

        // Act
        Optional<ConversationThread> found = threadRepository.findByThreadKey(thread.getThreadKey());

        // Assert
        assertTrue(found.isPresent());
        assertEquals(thread.getThreadKey(), found.get().getThreadKey());
        assertEquals(user.getId(), found.get().getUser().getId());
    }

    @Test
    void whenFindByThreadKey_andThreadDoesNotExist_thenReturnEmpty() {
        // Act
        Optional<ConversationThread> found = threadRepository.findByThreadKey("non-existent-key");

        // Assert
        assertTrue(found.isEmpty());
    }

    @Test
    @Transactional
    void whenFindByUserAndIsActiveTrueOrderByLastActivityAtDesc_thenReturnActiveThreads() {
        // Arrange
        RestUser user = createTestUser();
        restUserRepository.save(user);
        entityManager.flush();
        entityManager.clear();

        // Create threads
        ConversationThread thread1 = createThread(user, "thread-1", true, OffsetDateTime.now().minusHours(2));
        ConversationThread thread2 = createThread(user, "thread-2", true, OffsetDateTime.now().minusHours(1));
        ConversationThread thread3 = createThread(user, "thread-3", false, OffsetDateTime.now()); // Inactive
        
        // Save threads
        thread1 = threadRepository.save(thread1);
        thread2 = threadRepository.save(thread2);
        threadRepository.save(thread3);
        entityManager.flush();
        
        // Update lastActivityAt via SQL to bypass @PreUpdate
        OffsetDateTime time1 = OffsetDateTime.now().minusHours(2);
        OffsetDateTime time2 = OffsetDateTime.now().minusHours(1);
        entityManager.createNativeQuery(
                "UPDATE conversation_thread SET last_activity_at = :time WHERE id = :id")
                .setParameter("time", time1)
                .setParameter("id", thread1.getId())
                .executeUpdate();
        entityManager.createNativeQuery(
                "UPDATE conversation_thread SET last_activity_at = :time WHERE id = :id")
                .setParameter("time", time2)
                .setParameter("id", thread2.getId())
                .executeUpdate();
        
        entityManager.flush();
        entityManager.clear();

        // Act
        List<ConversationThread> found = threadRepository.findByUserAndIsActiveTrueOrderByLastActivityAtDesc(user);

        // Assert
        assertEquals(2, found.size());
        assertEquals("thread-2", found.get(0).getThreadKey()); // Most recent first
        assertEquals("thread-1", found.get(1).getThreadKey());
    }

    @Test
    @Transactional
    void whenFindByUserAndIsActiveTrueAndLastActivityAtBefore_thenReturnOldThreads() {
        // Arrange
        RestUser user = createTestUser();
        restUserRepository.save(user);
        entityManager.flush();
        entityManager.clear();

        OffsetDateTime cutoff = OffsetDateTime.now().minusHours(1);
        ConversationThread thread1 = createThread(user, "thread-1", true, OffsetDateTime.now().minusHours(2)); // Older
        ConversationThread thread2 = createThread(user, "thread-2", true, OffsetDateTime.now().minusMinutes(30)); // Newer
        
        thread1 = threadRepository.save(thread1);
        thread2 = threadRepository.save(thread2);
        entityManager.flush();
        
        // Update lastActivityAt via SQL to bypass @PreUpdate
        entityManager.createNativeQuery(
                "UPDATE conversation_thread SET last_activity_at = :time WHERE id = :id")
                .setParameter("time", OffsetDateTime.now().minusHours(2))
                .setParameter("id", thread1.getId())
                .executeUpdate();
        entityManager.createNativeQuery(
                "UPDATE conversation_thread SET last_activity_at = :time WHERE id = :id")
                .setParameter("time", OffsetDateTime.now().minusMinutes(30))
                .setParameter("id", thread2.getId())
                .executeUpdate();
        
        entityManager.flush();
        entityManager.clear();

        // Act
        List<ConversationThread> found = threadRepository.findByUserAndIsActiveTrueAndLastActivityAtBefore(user, cutoff);

        // Assert
        assertEquals(1, found.size());
        assertEquals("thread-1", found.get(0).getThreadKey());
    }

    @Test
    @Transactional
    void whenFindMostRecentActiveThread_thenReturnMostRecent() {
        // Arrange
        RestUser user = createTestUser();
        restUserRepository.save(user);
        entityManager.flush();
        entityManager.clear();

        // Create threads
        ConversationThread thread1 = createThread(user, "thread-1", true, null);
        ConversationThread thread2 = createThread(user, "thread-2", true, null);
        ConversationThread thread3 = createThread(user, "thread-3", false, null); // Inactive
        
        // Save all threads
        thread1 = threadRepository.save(thread1);
        thread2 = threadRepository.save(thread2);
        threadRepository.save(thread3);
        entityManager.flush();
        
        // Detach entities so Hibernate does not overwrite changes
        entityManager.detach(thread1);
        entityManager.detach(thread2);
        entityManager.detach(thread3);
        
        // Update lastActivityAt via SQL with different times
        OffsetDateTime time1 = OffsetDateTime.now().minusHours(2);
        OffsetDateTime time2 = OffsetDateTime.now().minusHours(1);
        entityManager.createNativeQuery(
                "UPDATE conversation_thread SET last_activity_at = :time WHERE id = :id")
                .setParameter("time", time1)
                .setParameter("id", thread1.getId())
                .executeUpdate();
        entityManager.createNativeQuery(
                "UPDATE conversation_thread SET last_activity_at = :time WHERE id = :id")
                .setParameter("time", time2)
                .setParameter("id", thread2.getId())
                .executeUpdate();
        
        entityManager.flush();
        entityManager.clear();

        // Verify data saved correctly via list
        List<ConversationThread> allActive = threadRepository.findByUserAndIsActiveTrueOrderByLastActivityAtDesc(user);
        assertEquals(2, allActive.size(), "Must have 2 active threads");
        assertEquals("thread-2", allActive.get(0).getThreadKey(), "First in list must be thread-2");
        
        // Verify findFirstByUserAndIsActiveTrueOrderByLastActivityAtDesc works
        Optional<ConversationThread> foundDirect = threadRepository.findFirstByUserAndIsActiveTrueOrderByLastActivityAtDesc(user);
        assertTrue(foundDirect.isPresent(), "findFirstBy must return a result");
        assertEquals("thread-2", foundDirect.get().getThreadKey(), "findFirstBy must return thread-2");

        // Act - use convenience alias method
        Optional<ConversationThread> found = threadRepository.findMostRecentActiveThread(user);

        // Assert
        assertTrue(found.isPresent(), "Active thread must be found");
        assertEquals("thread-2", found.get().getThreadKey(), "Most recent active must be thread-2");
    }

    @Test
    @Transactional
    void whenFindMostRecentActiveThread_andNoActiveThreads_thenReturnEmpty() {
        // Arrange
        RestUser user = createTestUser();
        restUserRepository.save(user);

        ConversationThread thread1 = createThread(user, "thread-1", false, OffsetDateTime.now());
        ConversationThread thread2 = createThread(user, "thread-2", false, OffsetDateTime.now());
        threadRepository.saveAll(List.of(thread1, thread2));

        // Act
        Optional<ConversationThread> found = threadRepository.findMostRecentActiveThread(user);

        // Assert
        assertTrue(found.isEmpty());
    }

    @Test
    @Transactional
    void whenSaveThread_thenThreadIsSaved() {
        // Arrange
        RestUser user = createTestUser();
        restUserRepository.save(user);

        ConversationThread thread = new ConversationThread();
        thread.setUser(user);
        thread.setThreadKey(UUID.randomUUID().toString());
        thread.setIsActive(true);
        thread.setLastActivityAt(OffsetDateTime.now());
        thread.setTotalMessages(0);
        thread.setTotalTokens(0L);
        thread.setMemoryBullets(List.of("fact1", "fact2"));

        // Act
        ConversationThread saved = threadRepository.save(thread);

        // Assert
        assertNotNull(saved.getId());
        assertEquals(thread.getThreadKey(), saved.getThreadKey());
        assertEquals(user.getId(), saved.getUser().getId());
        assertEquals(2, saved.getMemoryBullets().size());
        assertNotNull(saved.getCreatedAt());
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

    private ConversationThread createThread(RestUser user, String threadKey, boolean isActive, OffsetDateTime lastActivity) {
        ConversationThread thread = new ConversationThread();
        thread.setUser(user);
        thread.setThreadKey(threadKey);
        thread.setIsActive(isActive);
        thread.setLastActivityAt(lastActivity);
        thread.setTotalMessages(0);
        thread.setTotalTokens(0L);
        thread.setMemoryBullets(List.of());
        return thread;
    }
}
