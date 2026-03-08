package io.github.ngirchev.aibot.common.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Initializes admin at application startup.
 * Creates admin from config (Telegram ID or REST email).
 */
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai-bot.common.admin.enabled", havingValue = "true", matchIfMissing = false)
public class AdminInitializer {

    private final CoreCommonProperties coreCommonProperties;
    private final ApplicationContext applicationContext;

    @PostConstruct
    @Transactional
    public void initAdmin() {
        log.info("Initializing admin...");

        CoreCommonProperties.AdminProperties adminProperties = coreCommonProperties.getAdmin();
        if (adminProperties == null) {
            log.warn("Admin configuration not found, skipping initialization");
            return;
        }
        
        // Check if Telegram admin ID is set
        if (adminProperties.getTelegramId() != null) {
            Object telegramRepo = getBeanByClassName("io.github.ngirchev.aibot.telegram.repository.TelegramUserRepository");
            if (telegramRepo != null) {
                initTelegramAdmin(adminProperties.getTelegramId(), telegramRepo);
            }
        }
        
        // Check if REST admin (email) is set
        if (adminProperties.getRestEmail() != null) {
            Object restRepo = getBeanByClassName("io.github.ngirchev.aibot.rest.repository.RestUserRepository");
            if (restRepo != null) {
                initRestAdmin(adminProperties.getRestEmail(), restRepo);
            }
        }
        
        log.info("Admin initialization completed");
    }

    @SuppressWarnings("unchecked")
    private void initTelegramAdmin(Long telegramId, Object repository) {
        log.info("Creating/updating Telegram admin with ID: {}", telegramId);
        
        try {
            // Use reflection to work with TelegramUserRepository
            java.lang.reflect.Method findByTelegramId = repository.getClass().getMethod("findByTelegramId", Long.class);
            Optional<Object> existingUserOpt = (Optional<Object>) findByTelegramId.invoke(repository, telegramId);
            
            if (existingUserOpt.isPresent()) {
                Object user = existingUserOpt.get();
                java.lang.reflect.Method getIsAdmin = user.getClass().getMethod("getIsAdmin");
                Boolean isAdmin = (Boolean) getIsAdmin.invoke(user);
                
                if (!Boolean.TRUE.equals(isAdmin)) {
                    user.getClass().getMethod("setIsAdmin", Boolean.class).invoke(user, true);
                    user.getClass().getMethod("setIsPremium", Boolean.class).invoke(user, true);
                    user.getClass().getMethod("setIsBlocked", Boolean.class).invoke(user, false);
                    repository.getClass().getMethod("save", Object.class).invoke(repository, user);
                    log.info("Telegram user with ID {} updated to admin", telegramId);
                } else {
                    log.info("Telegram user with ID {} is already admin", telegramId);
                }
            } else {
                // Create new admin
            Object admin = Class.forName("io.github.ngirchev.aibot.telegram.model.TelegramUser").getDeclaredConstructor().newInstance();
                admin.getClass().getMethod("setTelegramId", Long.class).invoke(admin, telegramId);
                admin.getClass().getMethod("setUsername", String.class).invoke(admin, "admin_" + telegramId);
                admin.getClass().getMethod("setIsAdmin", Boolean.class).invoke(admin, true);
                admin.getClass().getMethod("setIsPremium", Boolean.class).invoke(admin, true);
                admin.getClass().getMethod("setIsBlocked", Boolean.class).invoke(admin, false);
                admin.getClass().getMethod("setCreatedAt", OffsetDateTime.class).invoke(admin, OffsetDateTime.now());
                admin.getClass().getMethod("setUpdatedAt", OffsetDateTime.class).invoke(admin, OffsetDateTime.now());
                admin.getClass().getMethod("setLastActivityAt", OffsetDateTime.class).invoke(admin, OffsetDateTime.now());
                repository.getClass().getMethod("save", Object.class).invoke(repository, admin);
                log.info("Created new Telegram admin with ID: {}", telegramId);
            }
        } catch (Exception e) {
            log.error("Error creating Telegram admin", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void initRestAdmin(String email, Object repository) {
        log.info("Creating/updating REST admin with email: {}", email);
        
        try {
            // Use reflection to work with RestUserRepository
            java.lang.reflect.Method findByEmail = repository.getClass().getMethod("findByEmail", String.class);
            Optional<Object> existingUserOpt = (Optional<Object>) findByEmail.invoke(repository, email);
            
            if (existingUserOpt.isPresent()) {
                Object user = existingUserOpt.get();
                java.lang.reflect.Method getIsAdmin = user.getClass().getMethod("getIsAdmin");
                Boolean isAdmin = (Boolean) getIsAdmin.invoke(user);
                
                if (!Boolean.TRUE.equals(isAdmin)) {
                    user.getClass().getMethod("setIsAdmin", Boolean.class).invoke(user, true);
                    user.getClass().getMethod("setIsPremium", Boolean.class).invoke(user, true);
                    user.getClass().getMethod("setIsBlocked", Boolean.class).invoke(user, false);
                    repository.getClass().getMethod("save", Object.class).invoke(repository, user);
                    log.info("REST user with email {} updated to admin", email);
                } else {
                    log.info("REST user with email {} is already admin", email);
                }
            } else {
                // Create new admin
            Object admin = Class.forName("io.github.ngirchev.aibot.rest.model.RestUser").getDeclaredConstructor().newInstance();
                admin.getClass().getMethod("setEmail", String.class).invoke(admin, email);
                admin.getClass().getMethod("setUsername", String.class).invoke(admin, email);
                admin.getClass().getMethod("setIsAdmin", Boolean.class).invoke(admin, true);
                admin.getClass().getMethod("setIsPremium", Boolean.class).invoke(admin, true);
                admin.getClass().getMethod("setIsBlocked", Boolean.class).invoke(admin, false);
                admin.getClass().getMethod("setCreatedAt", OffsetDateTime.class).invoke(admin, OffsetDateTime.now());
                admin.getClass().getMethod("setUpdatedAt", OffsetDateTime.class).invoke(admin, OffsetDateTime.now());
                admin.getClass().getMethod("setLastActivityAt", OffsetDateTime.class).invoke(admin, OffsetDateTime.now());
                repository.getClass().getMethod("save", Object.class).invoke(repository, admin);
                log.info("Created new REST admin with email: {} (API key can be set later)", email);
            }
        } catch (Exception e) {
            log.error("Error creating REST admin", e);
        }
    }

    private Object getBeanByClassName(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            String[] beanNames = applicationContext.getBeanNamesForType(clazz);
            if (beanNames.length > 0) {
                return applicationContext.getBean(beanNames[0]);
            }
        } catch (Exception e) {
            // Class or bean not found - normal for optional modules
            log.debug("Bean {} not found, skipping", className);
        }
        return null;
    }
}

