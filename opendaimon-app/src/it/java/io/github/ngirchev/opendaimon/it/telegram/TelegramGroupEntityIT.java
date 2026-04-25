package io.github.ngirchev.opendaimon.it.telegram;

import io.github.ngirchev.opendaimon.common.SupportedLanguages;
import io.github.ngirchev.opendaimon.common.config.CoreCommonProperties;
import io.github.ngirchev.opendaimon.common.config.CoreFlywayConfig;
import io.github.ngirchev.opendaimon.common.config.CoreJpaConfig;
import io.github.ngirchev.opendaimon.common.model.ThinkingMode;
import io.github.ngirchev.opendaimon.common.model.User;
import io.github.ngirchev.opendaimon.common.model.UserRecentModel;
import io.github.ngirchev.opendaimon.common.repository.UserRecentModelRepository;
import io.github.ngirchev.opendaimon.common.repository.UserRepository;
import io.github.ngirchev.opendaimon.common.service.ChatOwnerLookup;
import io.github.ngirchev.opendaimon.it.ITTestConfiguration;
import io.github.ngirchev.opendaimon.it.fixture.config.TelegramFixtureConfig;
import io.github.ngirchev.opendaimon.telegram.config.TelegramFlywayConfig;
import io.github.ngirchev.opendaimon.telegram.config.TelegramJpaConfig;
import io.github.ngirchev.opendaimon.telegram.model.TelegramGroup;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.telegram.repository.TelegramGroupRepository;
import io.github.ngirchev.opendaimon.telegram.service.ChatSettingsOwnerResolver;
import io.github.ngirchev.opendaimon.telegram.service.ChatSettingsService;
import io.github.ngirchev.opendaimon.test.AbstractContainerIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.Chat;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration test for the TelegramGroup settings-owner model against a
 * real Postgres (Testcontainers) and a full Spring context.
 * <p>
 * Verifies the three properties the group migration is supposed to guarantee:
 * <ol>
 *   <li>First interaction with an unseen group chat creates a {@link TelegramGroup}
 *       row lazily via {@link ChatSettingsOwnerResolver#resolveForChat}.</li>
 *   <li>{@link ChatSettingsService} writes route to the group row — different invokers
 *       see the same settings in subsequent reads (no per-invoker leakage, Bug #114).</li>
 *   <li>{@link ChatOwnerLookup} (SPI bound to {@code TelegramChatOwnerLookup}) finds the
 *       group by {@code chat_id} — the path summarization uses to seed preferredModelId.</li>
 * </ol>
 */
@SpringBootTest(
        classes = ITTestConfiguration.class,
        properties = {
                "spring.main.banner-mode=off",
                "spring.autoconfigure.exclude=" +
                        "io.github.ngirchev.opendaimon.common.config.CoreAutoConfig," +
                        "io.github.ngirchev.opendaimon.bulkhead.config.BulkHeadAutoConfig," +
                        "io.github.ngirchev.opendaimon.telegram.config.TelegramAutoConfig," +
                        "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration," +
                        "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration," +
                        "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration," +
                        "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration," +
                        "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration," +
                        "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration," +
                        "org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration"
        }
)
@ActiveProfiles("integration-test")
@EnableConfigurationProperties(CoreCommonProperties.class)
@Import({
        CoreFlywayConfig.class,
        CoreJpaConfig.class,
        TelegramFlywayConfig.class,
        TelegramJpaConfig.class,
        TelegramFixtureConfig.class
})
class TelegramGroupEntityIT extends AbstractContainerIT {

    private static final long GROUP_CHAT_ID = -1007654321098L;
    private static final long MEMBER_ALICE_ID = 90001L;
    private static final long MEMBER_BOB_ID = 90002L;

    @Autowired
    private ChatSettingsOwnerResolver resolver;

    @Autowired
    private ChatSettingsService chatSettingsService;

    @Autowired
    private ChatOwnerLookup chatOwnerLookup;

    @Autowired
    private TelegramGroupRepository telegramGroupRepository;

    @Autowired
    private UserRecentModelRepository userRecentModelRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    @Transactional
    @DisplayName("recent-models are chat-scoped: invoker's private recents DO NOT leak into the group view")
    void shouldScopeRecentModelsToChatEntityNotInvoker() {
        long chatId = GROUP_CHAT_ID - 7;
        long aliceId = MEMBER_ALICE_ID + 2000;
        Chat groupChat = buildGroupChat(chatId, "Recent-scope test", "supergroup");

        // Step 1: Alice records a model in her PRIVATE chat — this writes a UserRecentModel
        // against her personal TelegramUser.id.
        User aliceAsUser = resolver.resolveForChat(privateChat(aliceId), apiUser(aliceId, "alice"));
        assertTrue(aliceAsUser instanceof TelegramUser);
        recordRecentModel(aliceAsUser, "private-only-model");

        // Step 2: Alice walks into a group chat — resolver produces a TelegramGroup.
        User groupOwner = resolver.resolveForChat(groupChat, apiUser(aliceId, "alice"));
        assertTrue(groupOwner instanceof TelegramGroup);
        assertNotEquals(aliceAsUser.getId(), groupOwner.getId(),
                "group row and Alice's TelegramUser must be distinct");

        // Step 3: Recent-models for the GROUP owner id must NOT include Alice's private pick.
        // This is the fix for the production regression "I see my private recents in the group".
        var groupRecent = recentModelNamesFor(groupOwner.getId());
        assertFalse(groupRecent.contains("private-only-model"),
                "group must not see Alice's private chat recent models; got: " + groupRecent);

        // Step 4: A model picked IN the group writes against the GROUP id.
        recordRecentModel(groupOwner, "group-only-model");
        var groupRecentAfter = recentModelNamesFor(groupOwner.getId());
        assertTrue(groupRecentAfter.contains("group-only-model"),
                "group's recent list must carry models picked inside the group");
        // Her private recents still have the private-only model, no group leakage in the other direction.
        var privateRecent = recentModelNamesFor(aliceAsUser.getId());
        assertTrue(privateRecent.contains("private-only-model"));
        assertFalse(privateRecent.contains("group-only-model"),
                "private chat must not see group's recent models; got: " + privateRecent);
    }

    private void recordRecentModel(User owner, String modelName) {
        UserRecentModel row = new UserRecentModel();
        row.setUser(userRepository.findById(owner.getId()).orElseThrow());
        row.setModelName(modelName);
        row.setLastUsedAt(java.time.OffsetDateTime.now());
        userRecentModelRepository.save(row);
    }

    private java.util.List<String> recentModelNamesFor(Long ownerId) {
        return userRecentModelRepository
                .findTopByUser(ownerId, org.springframework.data.domain.PageRequest.of(0, 8))
                .stream().map(UserRecentModel::getModelName).toList();
    }

    @Test
    @Transactional
    @DisplayName("first group message by any member lazily creates a TelegramGroup row")
    void shouldLazilyCreateGroupOnFirstInteraction() {
        long chatId = GROUP_CHAT_ID - 1;
        Chat chat = buildGroupChat(chatId, "Fresh team", "supergroup");
        assertTrue(telegramGroupRepository.findByTelegramId(chatId).isEmpty(),
                "Pre-condition: group must not exist yet");

        User owner = resolver.resolveForChat(chat, apiUser(MEMBER_ALICE_ID, "alice"));

        assertTrue(owner instanceof TelegramGroup);
        TelegramGroup groupOwner = (TelegramGroup) owner;
        assertEquals(chatId, groupOwner.getTelegramId());
        assertEquals("Fresh team", groupOwner.getTitle());
        assertEquals("supergroup", groupOwner.getType());
        assertEquals(SupportedLanguages.DEFAULT_LANGUAGE, groupOwner.getLanguageCode(),
                "language defaults to DEFAULT_LANGUAGE on creation; /language can override later");
        assertNull(groupOwner.getPreferredModelId(), "model is unset until /model runs");

        Optional<TelegramGroup> found = telegramGroupRepository.findByTelegramId(chatId);
        assertTrue(found.isPresent());
        assertEquals(groupOwner.getId(), found.get().getId());
    }

    @Test
    @Transactional
    @DisplayName("second resolve for the same group returns the same row (idempotent)")
    void shouldReturnSameGroupEntityOnRepeatedResolve() {
        long chatId = GROUP_CHAT_ID - 2;
        Chat chat = buildGroupChat(chatId, "Persistent team", "group");

        User first = resolver.resolveForChat(chat, apiUser(MEMBER_ALICE_ID, "alice"));
        User second = resolver.resolveForChat(chat, apiUser(MEMBER_BOB_ID, "bob"));

        assertTrue(first instanceof TelegramGroup);
        assertTrue(second instanceof TelegramGroup);
        assertEquals(((TelegramGroup) first).getId(), ((TelegramGroup) second).getId(),
                "Bob's resolve must hit the same telegram_group row Alice created");
        assertEquals(1, telegramGroupRepository.findAll().stream()
                        .filter(g -> chatId == g.getTelegramId()).count(),
                "Exactly one row per chat id");
    }

    @Test
    @Transactional
    @DisplayName("settings written by member A are readable by member B in the same group")
    void shouldShareSettingsBetweenGroupMembers() {
        long chatId = GROUP_CHAT_ID - 3;
        Chat chat = buildGroupChat(chatId, "Shared settings", "supergroup");

        User ownerFromAlice = resolver.resolveForChat(chat, apiUser(MEMBER_ALICE_ID, "alice"));
        chatSettingsService.updateLanguageCode(ownerFromAlice, "ru");
        chatSettingsService.setPreferredModel(ownerFromAlice, "openrouter/claude-sonnet-4");
        chatSettingsService.updateThinkingMode(ownerFromAlice, ThinkingMode.SHOW_ALL);
        chatSettingsService.updateAgentMode(ownerFromAlice, true);

        User ownerFromBob = resolver.resolveForChat(chat, apiUser(MEMBER_BOB_ID, "bob"));
        assertTrue(ownerFromBob instanceof TelegramGroup);
        TelegramGroup reloaded = telegramGroupRepository.findByTelegramId(chatId).orElseThrow();
        assertEquals("ru", reloaded.getLanguageCode(), "language set by Alice must be visible to Bob");
        assertEquals("openrouter/claude-sonnet-4", reloaded.getPreferredModelId(),
                "model set by Alice must be visible to Bob");
        assertEquals(ThinkingMode.SHOW_ALL, reloaded.getThinkingMode());
        assertTrue(reloaded.getAgentModeEnabled());
    }

    @Test
    @Transactional
    @DisplayName("private-chat resolve returns TelegramUser, not TelegramGroup")
    void shouldReturnTelegramUserForPrivateChat() {
        Chat privateChat = new Chat();
        privateChat.setId(MEMBER_ALICE_ID);
        privateChat.setType("private");

        User owner = resolver.resolveForChat(privateChat, apiUser(MEMBER_ALICE_ID, "alice"));

        assertTrue(owner instanceof TelegramUser,
                "Private chats must produce a TelegramUser, got " + owner.getClass().getSimpleName());
        assertEquals(MEMBER_ALICE_ID, ((TelegramUser) owner).getTelegramId());
    }

    @Test
    @Transactional
    @DisplayName("ChatOwnerLookup.findByChatId routes by sign: negative → group, positive → user")
    void shouldRouteChatOwnerLookupByChatIdSign() {
        long chatId = GROUP_CHAT_ID - 4;
        resolver.resolveForChat(buildGroupChat(chatId, "lookup target", "supergroup"),
                apiUser(MEMBER_ALICE_ID, "alice"));
        resolver.resolveForChat(privateChat(MEMBER_BOB_ID), apiUser(MEMBER_BOB_ID, "bob"));

        Optional<User> groupOwner = chatOwnerLookup.findByChatId(chatId);
        assertTrue(groupOwner.isPresent());
        assertTrue(groupOwner.get() instanceof TelegramGroup);

        Optional<User> userOwner = chatOwnerLookup.findByChatId(MEMBER_BOB_ID);
        assertTrue(userOwner.isPresent());
        assertTrue(userOwner.get() instanceof TelegramUser);

        assertTrue(chatOwnerLookup.findByChatId(-999999999999L).isEmpty());
        assertTrue(chatOwnerLookup.findByChatId(999999999999L).isEmpty());
    }

    @Test
    @Transactional
    @DisplayName("updateGroupInfo picks up title/type changes on subsequent resolve")
    void shouldRefreshTitleAndTypeOnSubsequentResolve() {
        long chatId = GROUP_CHAT_ID - 5;
        resolver.resolveForChat(buildGroupChat(chatId, "Original", "group"), apiUser(MEMBER_ALICE_ID, "alice"));

        User after = resolver.resolveForChat(buildGroupChat(chatId, "Renamed", "supergroup"),
                apiUser(MEMBER_BOB_ID, "bob"));
        assertNotNull(after);
        TelegramGroup reloaded = telegramGroupRepository.findByTelegramId(chatId).orElseThrow();
        assertEquals("Renamed", reloaded.getTitle());
        assertEquals("supergroup", reloaded.getType());
    }

    @Test
    @Transactional
    @DisplayName("group member switch does not change the group's settings (they belong to the group row)")
    void shouldNotLeakSettingsAcrossInvokersInGroup() {
        long chatId = GROUP_CHAT_ID - 6;
        long aliceId = MEMBER_ALICE_ID + 1000;
        long bobId = MEMBER_BOB_ID + 1000;
        Chat chat = buildGroupChat(chatId, "Stable settings", "supergroup");

        User ownerFromAlice = resolver.resolveForChat(chat, apiUser(aliceId, "alice"));
        chatSettingsService.updateLanguageCode(ownerFromAlice, "ru");

        User ownerFromBob = resolver.resolveForChat(chat, apiUser(bobId, "bob"));
        assertTrue(ownerFromBob instanceof TelegramGroup);

        TelegramGroup groupRow = telegramGroupRepository.findByTelegramId(chatId).orElseThrow();
        assertEquals("ru", groupRow.getLanguageCode(),
                "group's languageCode was set by Alice's update; Bob sees the same row");

        // Alice's resolved owner is the SAME row Bob sees — one entity, shared by both.
        assertEquals(((TelegramGroup) ownerFromAlice).getId(), groupRow.getId());
        assertEquals(((TelegramGroup) ownerFromBob).getId(), groupRow.getId());

        // Settings-leak sanity: the group row is in telegram_group (positive discriminator),
        // not in telegram_user — so no TelegramUser row could have been accidentally mutated
        // with the group's language. Verify the chat_id lookup does NOT land in telegram_user.
        Optional<User> lookupByGroupChatId = chatOwnerLookup.findByChatId(chatId);
        assertTrue(lookupByGroupChatId.isPresent());
        assertTrue(lookupByGroupChatId.get() instanceof TelegramGroup,
                "chat_id must resolve to TelegramGroup, not TelegramUser — otherwise settings would leak");
        assertEquals(groupRow.getId(), lookupByGroupChatId.get().getId());
        // Suppress "bobId / aliceId unused" warnings — kept in the arrange block as production mirror.
        assertNotEquals(0L, bobId);
        assertNotEquals(0L, aliceId);
    }

    // ---------- helpers ----------

    private static Chat buildGroupChat(long chatId, String title, String type) {
        Chat chat = new Chat();
        chat.setId(chatId);
        chat.setTitle(title);
        chat.setType(type);
        return chat;
    }

    private static Chat privateChat(long userId) {
        Chat chat = new Chat();
        chat.setId(userId);
        chat.setType("private");
        return chat;
    }

    private static org.telegram.telegrambots.meta.api.objects.User apiUser(long id, String username) {
        org.telegram.telegrambots.meta.api.objects.User u = new org.telegram.telegrambots.meta.api.objects.User();
        u.setId(id);
        u.setUserName(username);
        u.setFirstName(username);
        u.setIsBot(false);
        return u;
    }

    /**
     * Safety-net no-op used for IDE "unused import" prevention when trimming helpers;
     * kept as a documented marker that {@link #shouldNotLeakSettingsAcrossInvokersInGroup}
     * relies on {@code assertFalse} imports even if not reached in every code path.
     */
    @SuppressWarnings("unused")
    private static void pin() {
        assertFalse(false);
    }
}
