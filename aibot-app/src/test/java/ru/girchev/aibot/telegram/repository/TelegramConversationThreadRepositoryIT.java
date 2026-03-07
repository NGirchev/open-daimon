package ru.girchev.aibot.telegram.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import ru.girchev.aibot.common.config.CoreJpaConfig;
import ru.girchev.aibot.common.config.CoreFlywayConfig;
import ru.girchev.aibot.common.model.ConversationThread;
import ru.girchev.aibot.common.repository.ConversationThreadRepository;
import ru.girchev.aibot.telegram.config.TelegramJpaConfig;
import ru.girchev.aibot.telegram.config.TelegramFlywayConfig;
import ru.girchev.aibot.telegram.model.TelegramUser;
import ru.girchev.aibot.test.TestDatabaseConfiguration;

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
class TelegramConversationThreadRepositoryIT {

    @Autowired
    private ConversationThreadRepository threadRepository;

    @Autowired
    private TelegramUserRepository telegramUserRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @Transactional
    void whenFindByThreadKey_thenReturnThread() {
        // Arrange
        TelegramUser user = createTestUser();
        telegramUserRepository.save(user);

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
        TelegramUser user = createTestUser();
        telegramUserRepository.save(user);
        entityManager.flush();
        entityManager.clear();

        // Создаем threads
        ConversationThread thread1 = createThread(user, "thread-1", true, OffsetDateTime.now().minusHours(2));
        ConversationThread thread2 = createThread(user, "thread-2", true, OffsetDateTime.now().minusHours(1));
        ConversationThread thread3 = createThread(user, "thread-3", false, OffsetDateTime.now()); // Неактивный
        
        // Сохраняем threads
        thread1 = threadRepository.save(thread1);
        thread2 = threadRepository.save(thread2);
        threadRepository.save(thread3);
        entityManager.flush();
        
        // Обновляем lastActivityAt напрямую через SQL, чтобы обойти @PreUpdate
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
        assertEquals("thread-2", found.get(0).getThreadKey()); // Более свежий первым
        assertEquals("thread-1", found.get(1).getThreadKey());
    }

    @Test
    @Transactional
    void whenFindByUserAndIsActiveTrueAndLastActivityAtBefore_thenReturnOldThreads() {
        // Arrange
        TelegramUser user = createTestUser();
        telegramUserRepository.save(user);
        entityManager.flush();
        entityManager.clear();

        OffsetDateTime cutoff = OffsetDateTime.now().minusHours(1);
        ConversationThread thread1 = createThread(user, "thread-1", true, OffsetDateTime.now().minusHours(2)); // Старый
        ConversationThread thread2 = createThread(user, "thread-2", true, OffsetDateTime.now().minusMinutes(30)); // Новый
        
        thread1 = threadRepository.save(thread1);
        thread2 = threadRepository.save(thread2);
        entityManager.flush();
        
        // Обновляем lastActivityAt напрямую через SQL, чтобы обойти @PreUpdate
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
        TelegramUser user = createTestUser();
        telegramUserRepository.save(user);
        entityManager.flush();
        entityManager.clear();

        // Создаем threads
        ConversationThread thread1 = createThread(user, "thread-1", true, null);
        ConversationThread thread2 = createThread(user, "thread-2", true, null);
        ConversationThread thread3 = createThread(user, "thread-3", false, null); // Неактивный
        
        // Сохраняем все threads
        thread1 = threadRepository.save(thread1);
        thread2 = threadRepository.save(thread2);
        threadRepository.save(thread3);
        entityManager.flush();
        
        // Отсоединяем объекты от контекста, чтобы Hibernate не перезаписывал изменения
        entityManager.detach(thread1);
        entityManager.detach(thread2);
        entityManager.detach(thread3);
        
        // Обновляем lastActivityAt напрямую через SQL с разными временами
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

        // Проверяем, что данные правильно сохранены через список
        List<ConversationThread> allActive = threadRepository.findByUserAndIsActiveTrueOrderByLastActivityAtDesc(user);
        assertEquals(2, allActive.size(), "Должно быть 2 активных thread");
        assertEquals("thread-2", allActive.get(0).getThreadKey(), "Первый в списке должен быть thread-2");
        
        // Проверяем, что findFirstByUserAndIsActiveTrueOrderByLastActivityAtDesc работает
        Optional<ConversationThread> foundDirect = threadRepository.findFirstByUserAndIsActiveTrueOrderByLastActivityAtDesc(user);
        assertTrue(foundDirect.isPresent(), "findFirstBy должен вернуть результат");
        assertEquals("thread-2", foundDirect.get().getThreadKey(), "findFirstBy должен вернуть thread-2");

        // Act - используем удобный метод-алиас
        Optional<ConversationThread> found = threadRepository.findMostRecentActiveThread(user);

        // Assert
        assertTrue(found.isPresent(), "Должен быть найден активный thread");
        assertEquals("thread-2", found.get().getThreadKey(), "Самый свежий активный должен быть thread-2");
    }

    @Test
    @Transactional
    void whenFindMostRecentActiveThread_andNoActiveThreads_thenReturnEmpty() {
        // Arrange
        TelegramUser user = createTestUser();
        telegramUserRepository.save(user);

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
        TelegramUser user = createTestUser();
        telegramUserRepository.save(user);

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

    // Вспомогательные методы
    private TelegramUser createTestUser() {
        TelegramUser user = new TelegramUser();
        user.setTelegramId(123L);
        user.setUsername("testuser");
        user.setFirstName("Test");
        user.setLastName("User");
        user.setCreatedAt(OffsetDateTime.now());
        user.setUpdatedAt(OffsetDateTime.now());
        user.setLastActivityAt(OffsetDateTime.now());
        return user;
    }

    private ConversationThread createThread(TelegramUser user, String threadKey, boolean isActive, OffsetDateTime lastActivity) {
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
