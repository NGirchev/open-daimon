package io.github.ngirchev.opendaimon.it.telegram.repository;

import io.github.ngirchev.opendaimon.common.config.CoreFlywayConfig;
import io.github.ngirchev.opendaimon.common.config.CoreJpaConfig;
import io.github.ngirchev.opendaimon.common.model.UserRecentModel;
import io.github.ngirchev.opendaimon.common.repository.UserRecentModelRepository;
import io.github.ngirchev.opendaimon.telegram.config.TelegramFlywayConfig;
import io.github.ngirchev.opendaimon.telegram.config.TelegramJpaConfig;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.telegram.repository.TelegramUserRepository;
import io.github.ngirchev.opendaimon.test.AbstractContainerIT;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        CoreJpaConfig.class,
        TelegramJpaConfig.class,
        CoreFlywayConfig.class,
        TelegramFlywayConfig.class
})
class UserRecentModelRepositoryIT extends AbstractContainerIT {

    @Autowired
    private UserRecentModelRepository userRecentModelRepository;

    @Autowired
    private TelegramUserRepository telegramUserRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @Transactional
    void shouldReturnEntryWhenFoundByUserAndModelName() {
        TelegramUser user = saveUser(1L);

        UserRecentModel entry = new UserRecentModel();
        entry.setUser(user);
        entry.setModelName("gpt-4");
        entry.setLastUsedAt(OffsetDateTime.now());
        userRecentModelRepository.save(entry);

        Optional<UserRecentModel> found = userRecentModelRepository
                .findByUserIdAndModelName(user.getId(), "gpt-4");

        assertThat(found).isPresent();
        assertThat(found.get().getModelName()).isEqualTo("gpt-4");
        assertThat(found.get().getUser().getId()).isEqualTo(user.getId());
    }

    @Test
    @Transactional
    void shouldRejectDuplicateUserModelPair() {
        TelegramUser user = saveUser(2L);

        UserRecentModel first = new UserRecentModel();
        first.setUser(user);
        first.setModelName("claude-opus");
        first.setLastUsedAt(OffsetDateTime.now());
        userRecentModelRepository.save(first);

        UserRecentModel duplicate = new UserRecentModel();
        duplicate.setUser(user);
        duplicate.setModelName("claude-opus");
        duplicate.setLastUsedAt(OffsetDateTime.now());

        assertThatThrownBy(() -> {
            userRecentModelRepository.saveAndFlush(duplicate);
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void shouldReturnTopEntriesOrderedByLastUsedDesc() {
        TelegramUser user = saveUser(3L);

        OffsetDateTime now = OffsetDateTime.now();
        UserRecentModel oldest = save(user, "old-model", now.minusHours(3));
        UserRecentModel middle = save(user, "mid-model", now.minusHours(2));
        UserRecentModel newest = save(user, "new-model", now.minusHours(1));
        entityManager.flush();

        // Override lastUsedAt via native SQL because @PreUpdate clobbers manual values.
        updateLastUsed(oldest.getId(), now.minusHours(3));
        updateLastUsed(middle.getId(), now.minusHours(2));
        updateLastUsed(newest.getId(), now.minusHours(1));
        entityManager.flush();
        entityManager.clear();

        List<UserRecentModel> top = userRecentModelRepository.findTopByUser(
                user.getId(), PageRequest.of(0, 8));

        assertThat(top).extracting(UserRecentModel::getModelName)
                .containsExactly("new-model", "mid-model", "old-model");
    }

    @Test
    @Transactional
    void shouldDeleteRowsOutsideRetainList() {
        TelegramUser user = saveUser(4L);

        UserRecentModel keep = save(user, "keep-me", OffsetDateTime.now());
        UserRecentModel drop = save(user, "drop-me", OffsetDateTime.now().minusDays(1));
        entityManager.flush();

        int deleted = userRecentModelRepository.deleteByUserIdAndIdNotIn(
                user.getId(), List.of(keep.getId()));
        entityManager.flush();
        entityManager.clear();

        assertThat(deleted).isEqualTo(1);
        assertThat(userRecentModelRepository.findByUserIdAndModelName(user.getId(), "keep-me"))
                .isPresent();
        assertThat(userRecentModelRepository.findByUserIdAndModelName(user.getId(), "drop-me"))
                .isEmpty();
        assertThat(drop.getId()).isNotNull();
    }

    @Test
    @Transactional
    void shouldCascadeDeleteWhenUserRemoved() {
        TelegramUser user = saveUser(5L);
        save(user, "shadow-model", OffsetDateTime.now());
        entityManager.flush();
        Long userId = user.getId();
        entityManager.clear();

        // Re-attach via delete-by-id to avoid cascading from a detached graph.
        telegramUserRepository.deleteById(userId);
        entityManager.flush();
        entityManager.clear();

        List<UserRecentModel> remaining = userRecentModelRepository.findTopByUser(
                userId, PageRequest.of(0, 8));
        assertThat(remaining).isEmpty();
    }

    // Helpers

    private TelegramUser saveUser(long telegramId) {
        TelegramUser user = new TelegramUser();
        user.setTelegramId(telegramId);
        user.setUsername("u" + telegramId);
        user.setFirstName("Recent");
        user.setLastName("Tester");
        user.setCreatedAt(OffsetDateTime.now());
        user.setUpdatedAt(OffsetDateTime.now());
        user.setLastActivityAt(OffsetDateTime.now());
        user.setIsAdmin(false);
        user.setIsPremium(false);
        user.setIsBlocked(false);
        return telegramUserRepository.save(user);
    }

    private UserRecentModel save(TelegramUser user, String modelName, OffsetDateTime at) {
        UserRecentModel entry = new UserRecentModel();
        entry.setUser(user);
        entry.setModelName(modelName);
        entry.setLastUsedAt(at);
        return userRecentModelRepository.save(entry);
    }

    private void updateLastUsed(Long id, OffsetDateTime at) {
        entityManager.createNativeQuery(
                        "UPDATE user_recent_model SET last_used_at = :at WHERE id = :id")
                .setParameter("at", at)
                .setParameter("id", id)
                .executeUpdate();
    }
}
