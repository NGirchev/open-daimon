package io.github.ngirchev.opendaimon.telegram.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TelegramPropertiesTest {

    private TelegramProperties properties;

    @BeforeEach
    void setUp() {
        properties = new TelegramProperties();
        properties.setToken("test-token");
        properties.setUsername("test-bot");
        properties.setMaxMessageLength(4096);
    }

    @Test
    void getAllAccessChannels_whenLevelsNullOrEmpty_returnsEmptySet() {
        properties.parseWhitelistExceptions();
        assertTrue(properties.getAllAccessChannels().isEmpty());
    }

    @Test
    void getAllAccessChannels_whenChannelsConfigured_returnsUnionOfAllLevels() {
        TelegramProperties.AccessConfig accessConfig = new TelegramProperties.AccessConfig();
        TelegramProperties.AccessConfig.LevelConfig admin = new TelegramProperties.AccessConfig.LevelConfig();
        admin.setChannels(Set.of("admin-channel", "@admin_group"));
        TelegramProperties.AccessConfig.LevelConfig vip = new TelegramProperties.AccessConfig.LevelConfig();
        vip.setChannels(Set.of("vip-channel"));
        TelegramProperties.AccessConfig.LevelConfig regular = new TelegramProperties.AccessConfig.LevelConfig();
        regular.setChannels(Set.of("common-channel", "vip-channel"));

        accessConfig.setAdmin(admin);
        accessConfig.setVip(vip);
        accessConfig.setRegular(regular);
        properties.setAccess(accessConfig);

        properties.parseWhitelistExceptions();

        assertEquals(
                Set.of("admin-channel", "@admin_group", "vip-channel", "common-channel"),
                properties.getAllAccessChannels()
        );
    }

    @Test
    void commands_gettersSetters_work() {
        TelegramProperties.Commands commands = new TelegramProperties.Commands();
        commands.setStartEnabled(true);
        commands.setRoleEnabled(true);
        commands.setMessageEnabled(false);
        commands.setBugreportEnabled(true);
        commands.setNewthreadEnabled(true);
        commands.setHistoryEnabled(true);
        commands.setThreadsEnabled(true);
        commands.setLanguageEnabled(false);

        assertTrue(commands.isStartEnabled());
        assertTrue(commands.isRoleEnabled());
        assertFalse(commands.isMessageEnabled());
        assertTrue(commands.isBugreportEnabled());
        assertTrue(commands.isNewthreadEnabled());
        assertTrue(commands.isHistoryEnabled());
        assertTrue(commands.isThreadsEnabled());
        assertFalse(commands.isLanguageEnabled());
    }

    @Test
    void optionalNumericFields_canBeSet() {
        properties.setLongPollingSocketTimeoutSeconds(50);
        properties.setGetUpdatesTimeoutSeconds(25);
        assertEquals(50, properties.getLongPollingSocketTimeoutSeconds());
        assertEquals(25, properties.getGetUpdatesTimeoutSeconds());
    }
}
