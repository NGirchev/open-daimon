package io.github.ngirchev.opendaimon.it.telegram.repository;

import io.github.ngirchev.opendaimon.common.config.CoreFlywayConfig;
import io.github.ngirchev.opendaimon.common.config.CoreJpaConfig;
import io.github.ngirchev.opendaimon.common.model.ThinkingMode;
import io.github.ngirchev.opendaimon.common.model.User;
import io.github.ngirchev.opendaimon.common.repository.UserRepository;
import io.github.ngirchev.opendaimon.telegram.config.TelegramFlywayConfig;
import io.github.ngirchev.opendaimon.telegram.config.TelegramJpaConfig;
import io.github.ngirchev.opendaimon.telegram.model.TelegramGroup;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.telegram.repository.TelegramGroupRepository;
import io.github.ngirchev.opendaimon.telegram.repository.TelegramUserRepository;
import io.github.ngirchev.opendaimon.test.AbstractContainerIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for {@link TelegramGroupRepository} + Flyway V3 migration +
 * JOINED inheritance mapping against a real Postgres (Testcontainers).
 * <p>
 * Verifies the Stage 1 migration actually applies (a failing migration would block
 * context startup), the {@code telegram_group} child table is populated correctly
 * with the discriminator {@code TELEGRAM_GROUP}, and polymorphic queries through the
 * base {@link UserRepository} return the subtype instance.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        CoreJpaConfig.class,
        TelegramJpaConfig.class,
        CoreFlywayConfig.class,
        TelegramFlywayConfig.class
})
class TelegramGroupRepositoryIT extends AbstractContainerIT {

    private static final Long GROUP_CHAT_ID = -1001234567890L;

    @Autowired
    private TelegramGroupRepository telegramGroupRepository;

    @Autowired
    private TelegramUserRepository telegramUserRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("save + findByTelegramId round-trip populates the V3 telegram_group table")
    void shouldSaveAndLoadTelegramGroupByChatId() {
        TelegramGroup group = buildGroup(GROUP_CHAT_ID, "DevOps team", "supergroup");
        group.setLanguageCode("ru");
        group.setPreferredModelId("openrouter/claude-sonnet-4");
        group.setAgentModeEnabled(true);
        group.setThinkingMode(ThinkingMode.SHOW_ALL);
        group.setMenuVersionHash("deadbeef");
        TelegramGroup saved = telegramGroupRepository.save(group);

        assertNotNull(saved.getId());

        Optional<TelegramGroup> found = telegramGroupRepository.findByTelegramId(GROUP_CHAT_ID);
        assertTrue(found.isPresent());
        TelegramGroup loaded = found.get();
        assertEquals(GROUP_CHAT_ID, loaded.getTelegramId());
        assertEquals("DevOps team", loaded.getTitle());
        assertEquals("supergroup", loaded.getType());
        assertEquals("ru", loaded.getLanguageCode());
        assertEquals("openrouter/claude-sonnet-4", loaded.getPreferredModelId());
        assertTrue(loaded.getAgentModeEnabled());
        assertEquals(ThinkingMode.SHOW_ALL, loaded.getThinkingMode());
        assertEquals("deadbeef", loaded.getMenuVersionHash());
    }

    @Test
    @DisplayName("existsByTelegramId correctly reflects presence in telegram_group")
    void shouldReportExistenceByChatId() {
        assertFalse(telegramGroupRepository.existsByTelegramId(GROUP_CHAT_ID));
        telegramGroupRepository.save(buildGroup(GROUP_CHAT_ID, "g", "group"));
        assertTrue(telegramGroupRepository.existsByTelegramId(GROUP_CHAT_ID));
    }

    @Test
    @DisplayName("polymorphic UserRepository.findById returns TelegramGroup subtype via discriminator TELEGRAM_GROUP")
    void shouldReturnTelegramGroupThroughPolymorphicUserRepository() {
        TelegramGroup saved = telegramGroupRepository.save(buildGroup(GROUP_CHAT_ID, "polymorphic test", "group"));

        Optional<User> found = userRepository.findById(saved.getId());

        assertTrue(found.isPresent(), "Base UserRepository must see the subtype via JOINED inheritance");
        User loaded = found.get();
        assertTrue(loaded instanceof TelegramGroup,
                "Expected TelegramGroup via discriminator, got " + loaded.getClass().getSimpleName());
    }

    @Test
    @DisplayName("TelegramUser and TelegramGroup coexist with distinct discriminators; chat_id namespaces do not collide")
    void shouldCoexistWithTelegramUserUnderSameBaseTable() {
        Long privateChatId = 42L;
        TelegramUser user = new TelegramUser();
        user.setTelegramId(privateChatId);
        user.setUsername("alice");
        OffsetDateTime now = OffsetDateTime.now();
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        user.setLastActivityAt(now);
        user.setIsAdmin(false);
        user.setIsPremium(false);
        user.setIsBlocked(false);
        user.setThinkingMode(ThinkingMode.HIDE_REASONING);
        TelegramUser savedUser = telegramUserRepository.save(user);

        TelegramGroup group = telegramGroupRepository.save(buildGroup(GROUP_CHAT_ID, "group", "supergroup"));

        // UserRepository.findById on the user's numeric id returns a TelegramUser.
        User userAsBase = userRepository.findById(savedUser.getId()).orElseThrow();
        assertTrue(userAsBase instanceof TelegramUser);
        // UserRepository.findById on the group's numeric id returns a TelegramGroup.
        User groupAsBase = userRepository.findById(group.getId()).orElseThrow();
        assertTrue(groupAsBase instanceof TelegramGroup);

        // Queries keyed on telegram_id hit the correct child table:
        assertTrue(telegramUserRepository.findByTelegramId(privateChatId).isPresent());
        assertTrue(telegramGroupRepository.findByTelegramId(GROUP_CHAT_ID).isPresent());
        assertTrue(telegramUserRepository.findByTelegramId(GROUP_CHAT_ID).isEmpty(),
                "Group chat_id must not leak into telegram_user child table");
        assertTrue(telegramGroupRepository.findByTelegramId(privateChatId).isEmpty(),
                "Private chat user id must not leak into telegram_group child table");
    }

    @Test
    @DisplayName("fresh group defaults: nullable fields land as null in DB, not implicit values")
    void shouldPersistFreshGroupWithNullableDefaults() {
        TelegramGroup fresh = buildGroup(GROUP_CHAT_ID, "fresh", "group");
        TelegramGroup saved = telegramGroupRepository.save(fresh);

        assertNotNull(saved.getId());
        assertNull(saved.getLanguageCode(), "languageCode must stay null until /language is invoked");
        assertNull(saved.getPreferredModelId(), "preferredModelId must stay null until /model is invoked");
        assertNull(saved.getMenuVersionHash(), "menuVersionHash must stay null until first menu reconcile");
    }

    private static TelegramGroup buildGroup(Long chatId, String title, String type) {
        TelegramGroup group = new TelegramGroup();
        group.setTelegramId(chatId);
        group.setTitle(title);
        group.setType(type);
        OffsetDateTime now = OffsetDateTime.now();
        group.setCreatedAt(now);
        group.setUpdatedAt(now);
        group.setLastActivityAt(now);
        group.setIsAdmin(false);
        group.setIsPremium(false);
        group.setIsBlocked(false);
        group.setThinkingMode(ThinkingMode.HIDE_REASONING);
        return group;
    }
}
