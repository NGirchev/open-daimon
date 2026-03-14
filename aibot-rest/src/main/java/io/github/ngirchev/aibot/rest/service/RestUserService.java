package io.github.ngirchev.aibot.rest.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import io.github.ngirchev.aibot.bulkhead.model.UserPriority;
import io.github.ngirchev.aibot.common.model.AssistantRole;
import io.github.ngirchev.aibot.common.service.AssistantRoleService;
import io.github.ngirchev.aibot.rest.config.RestProperties;
import io.github.ngirchev.aibot.rest.model.RestUser;
import io.github.ngirchev.aibot.rest.repository.RestUserRepository;

import java.time.OffsetDateTime;
import java.util.Optional;

@RequiredArgsConstructor
@Slf4j
public class RestUserService {
    
    private final RestUserRepository restUserRepository;
    private final AssistantRoleService assistantRoleService;
    private final RestProperties restProperties;
    
    /**
     * Gets or creates user by email
     */
    @Transactional
    public RestUser getOrCreateUser(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email is required for REST user");
        }
        String normalizedEmail = email.trim();
        return restUserRepository.findByEmail(normalizedEmail)
            .map(existing -> {
                log.info("REST user already exists: id={}, email='{}', isAdmin={}, isPremium={}, isBlocked={}",
                        existing.getId(), existing.getEmail(), existing.getIsAdmin(), existing.getIsPremium(), existing.getIsBlocked());
                return existing;
            })
            .orElseGet(() -> {
                RestUser newUser = new RestUser();
                newUser.setEmail(normalizedEmail);
                newUser.setUsername(normalizedEmail);
                OffsetDateTime now = OffsetDateTime.now();
                newUser.setCreatedAt(now);
                newUser.setUpdatedAt(now);
                newUser.setLastActivityAt(now);
                newUser.setIsBlocked(false);
                newUser.setIsPremium(false);
                newUser.setIsAdmin(false);
                applyAccessLevelFlags(newUser);
                log.info("Saving new REST user: email='{}', username='{}', isAdmin={}, isPremium={}, isBlocked={}",
                        newUser.getEmail(), newUser.getUsername(), newUser.getIsAdmin(), newUser.getIsPremium(), newUser.getIsBlocked());

                RestUser saved = restUserRepository.save(newUser);
                log.info("Saved REST user (entity): id={}, email='{}', isAdmin={}, isPremium={}, isBlocked={}",
                        saved.getId(), saved.getEmail(), saved.getIsAdmin(), saved.getIsPremium(), saved.getIsBlocked());

                restUserRepository.findById(saved.getId()).ifPresent(dbUser ->
                        log.info("Saved REST user (db read): id={}, email='{}', isAdmin={}, isPremium={}, isBlocked={}",
                                dbUser.getId(), dbUser.getEmail(), dbUser.getIsAdmin(), dbUser.getIsPremium(), dbUser.getIsBlocked())
                );
                return saved;
            });
    }
    
    private void applyAccessLevelFlags(RestUser user) {
        if (restProperties == null || restProperties.getAccess() == null) {
            return;
        }
        var access = restProperties.getAccess();
        var admin = access.getAdmin();
        if (admin != null && admin.getEmails() != null && admin.getEmails().contains(user.getEmail())) {
            user.setIsAdmin(true);
            user.setIsPremium(true);
            user.setIsBlocked(false);
        }
    }

    /**
     * Applies flags by access level (strict matrix). Used for startup and explicit level assignment.
     */
    public static void applyFlagsByLevel(RestUser user, UserPriority level) {
        if (level == null) {
            return;
        }
        switch (level) {
            case ADMIN -> {
                user.setIsAdmin(true);
                user.setIsPremium(true);
                user.setIsBlocked(false);
            }
            case VIP -> {
                user.setIsAdmin(false);
                user.setIsPremium(true);
                user.setIsBlocked(false);
            }
            case REGULAR, BLOCKED -> {
                user.setIsAdmin(false);
                user.setIsPremium(false);
                user.setIsBlocked(level == UserPriority.BLOCKED);
            }
        }
    }

    /**
     * Ensures a REST user exists with the given email and access level. Creates or updates and applies strict flag matrix.
     */
    @Transactional
    public RestUser ensureUserWithLevel(String email, UserPriority level) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email is required for REST user");
        }
        String normalizedEmail = email.trim();
        RestUser user = restUserRepository.findByEmail(normalizedEmail).orElseGet(() -> {
            RestUser newUser = new RestUser();
            newUser.setEmail(normalizedEmail);
            newUser.setUsername(normalizedEmail);
            OffsetDateTime now = OffsetDateTime.now();
            newUser.setCreatedAt(now);
            newUser.setUpdatedAt(now);
            newUser.setLastActivityAt(now);
            newUser.setIsBlocked(false);
            newUser.setIsPremium(false);
            newUser.setIsAdmin(false);
            return restUserRepository.save(newUser);
        });
        applyFlagsByLevel(user, level);
        OffsetDateTime now = OffsetDateTime.now();
        user.setUpdatedAt(now);
        user.setLastActivityAt(now);
        RestUser saved = restUserRepository.save(user);
        log.info("REST user ensured: id={}, email='{}', level={}, isAdmin={}, isPremium={}, isBlocked={}",
                saved.getId(), saved.getEmail(), level, saved.getIsAdmin(), saved.getIsPremium(), saved.getIsBlocked());
        return saved;
    }
    
    /**
     * Gets active assistant role for user.
     * @param user user
     * @param defaultContent default role content
     * @return active role
     */
    @Transactional
    public AssistantRole getOrCreateAssistantRole(RestUser user, String defaultContent) {
        // Important: often receives detached user (method called from bulkhead thread).
        // So we reload user into current session first, then init role.
        String email = user.getEmail();
        if (email == null) {
            throw new IllegalArgumentException("email is null");
        }

        RestUser managedUser = restUserRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Check if user has role
        AssistantRole role = managedUser.getCurrentAssistantRole();
        if (role == null) {
            // Get or create role
            role = assistantRoleService.getOrCreateDefaultRole(managedUser, defaultContent);

            // Save reference in user
            managedUser.setCurrentAssistantRole(role);
            restUserRepository.save(managedUser);
        }

        // Force-init role fields inside transaction
        // so we can use them safely outside Hibernate Session
        role.getId();
        role.getVersion();
        role.getContent();

        return role;
    }
    
    /**
     * Finds user by email
     */
    public Optional<RestUser> findByEmail(String email) {
        return restUserRepository.findByEmail(email);
    }
    
    /**
     * Finds user by id
     */
    public Optional<RestUser> findById(Long id) {
        return restUserRepository.findById(id);
    }
}

