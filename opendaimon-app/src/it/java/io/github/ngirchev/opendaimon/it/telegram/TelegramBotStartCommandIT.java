package io.github.ngirchev.opendaimon.it.telegram;

import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.command.ICommandType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import io.github.ngirchev.opendaimon.common.model.AssistantRole;
import io.github.ngirchev.opendaimon.common.service.AssistantRoleService;
import io.github.ngirchev.opendaimon.common.service.CommandSyncService;
import io.github.ngirchev.opendaimon.it.ITTestConfiguration;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.config.TelegramProperties;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUserSession;
import io.github.ngirchev.opendaimon.telegram.repository.TelegramUserRepository;
import io.github.ngirchev.opendaimon.telegram.repository.TelegramUserSessionRepository;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserSessionService;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = ITTestConfiguration.class)
@EnableConfigurationProperties(TelegramProperties.class)
@Import({
        TelegramBotStartCommandIT.JpaTestConfig.class,
        TelegramBotStartCommandIT.TestConfig.class
})
@TestPropertySource(properties = {
        "open-daimon.telegram.enabled=true",
        "open-daimon.telegram.token=test-token",
        "open-daimon.telegram.username=test-bot",
        "open-daimon.telegram.start-message=Test welcome message",
        "open-daimon.telegram.max-message-length=4096",
        "spring.autoconfigure.exclude=" +
                "io.github.ngirchev.opendaimon.telegram.config.TelegramAutoConfig," +
                "io.github.ngirchev.opendaimon.common.config.CoreAutoConfig," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration," +
                "io.github.ngirchev.opendaimon.ai.springai.config.SpringAIAutoConfig",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:telegrambotstart;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driverClassName=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.ai.openai.api-key=mock-key"
})
class TelegramBotStartCommandIT {

    @Configuration
    @EntityScan(basePackageClasses = {
            io.github.ngirchev.opendaimon.common.model.User.class,
            AssistantRole.class,
            TelegramUser.class,
            TelegramUserSession.class
    })
    @EnableJpaRepositories(basePackageClasses = {
            TelegramUserRepository.class,
            TelegramUserSessionRepository.class
    })
    static class JpaTestConfig {
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        public AssistantRoleService assistantRoleService() {
            return new AssistantRoleService() {
                @Override
                public Optional<AssistantRole> getActiveRole(io.github.ngirchev.opendaimon.common.model.User user) {
                    return Optional.empty();
                }

                @Override
                public AssistantRole createOrGetRole(io.github.ngirchev.opendaimon.common.model.User user, String content) {
                    throw new UnsupportedOperationException("Not needed for this test");
                }

                @Override
                public void setActiveRole(AssistantRole role) {
                    // no-op
                }

                @Override
                public AssistantRole updateActiveRole(io.github.ngirchev.opendaimon.common.model.User user, String content) {
                    throw new UnsupportedOperationException("Not needed for this test");
                }

                @Override
                public void incrementUsage(AssistantRole role) {
                    // no-op
                }

                @Override
                public List<AssistantRole> getAllUserRoles(io.github.ngirchev.opendaimon.common.model.User user) {
                    return List.of();
                }

                @Override
                public Optional<AssistantRole> getRoleByVersion(io.github.ngirchev.opendaimon.common.model.User user, Integer version) {
                    return Optional.empty();
                }

                @Override
                public int cleanupUnusedRoles(OffsetDateTime thresholdDate) {
                    return 0;
                }

                @Override
                public List<AssistantRole> findUnusedRoles(OffsetDateTime thresholdDate) {
                    return List.of();
                }

                @Override
                public AssistantRole getOrCreateDefaultRole(io.github.ngirchev.opendaimon.common.model.User user, String defaultContent) {
                    throw new UnsupportedOperationException("Not needed for this test");
                }

                @Override
                public Optional<AssistantRole> findById(Long roleId) {
                    return Optional.empty();
                }
            };
        }

        @Bean
        public TelegramUserSessionService telegramUserSessionService(
                TelegramUserSessionRepository telegramUserSessionRepository,
                TelegramUserRepository telegramUserRepository) {
            return new TelegramUserSessionService(telegramUserSessionRepository, telegramUserRepository);
        }

        @Bean
        public TelegramUserService telegramUserService(
                TelegramUserRepository telegramUserRepository,
                TelegramUserSessionService telegramUserSessionService,
                AssistantRoleService assistantRoleService) {
            return new TelegramUserService(telegramUserRepository, telegramUserSessionService, assistantRoleService);
        }

        @Bean
        public CommandSyncService commandSyncService() {
            return new CommandSyncService(null, null, null) {
                @Override
                public <T extends ICommandType, C extends ICommand<T>, R> R syncAndHandle(C command) {
                    throw new UnsupportedOperationException("Not needed for this test");
                }
            };
        }

        @Bean
        public TestableTelegramBot telegramBot(
                TelegramProperties telegramProperties,
                CommandSyncService commandSyncService,
                TelegramUserService telegramUserService) {
            return new TestableTelegramBot(telegramProperties, commandSyncService, telegramUserService);
        }
    }

    /**
     * Test wrapper to access protected methods of TelegramBot.
     */
    static class TestableTelegramBot extends TelegramBot {
        TestableTelegramBot(TelegramProperties config, CommandSyncService commandSyncService, TelegramUserService userService) {
            super(config, commandSyncService, userService);
        }

        public TelegramCommand testMapToTelegramTextCommand(Update update) {
            return mapToTelegramTextCommand(update);
        }
    }

    @Autowired
    private TestableTelegramBot telegramBot;
    @Autowired
    private TelegramUserRepository telegramUserRepository;

    @Test
    void whenStartCommandFromNewUser_thenMappingCreatesUserAndDoesNotThrow() {
        Long telegramId = 350001752L;

        assertTrue(telegramUserRepository.findByTelegramId(telegramId).isEmpty());

        User from = new User();
        from.setId(telegramId);
        from.setUserName("new_user");
        from.setFirstName("New");
        from.setLastName("User");

        Chat chat = new Chat();
        chat.setId(telegramId);

        Message message = new Message();
        message.setMessageId(1);
        message.setFrom(from);
        message.setChat(chat);
        message.setText("/start");

        Update update = new Update();
        update.setMessage(message);

        TelegramCommand mapped = assertDoesNotThrow(() -> telegramBot.testMapToTelegramTextCommand(update));
        assertNotNull(mapped);
        assertEquals("/start", mapped.commandType().command());

        var savedUser = telegramUserRepository.findByTelegramId(telegramId).orElseThrow();
        assertEquals(savedUser.getId(), mapped.userId());
    }
}
