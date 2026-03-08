package io.github.ngirchev.aibot.rest.service;

import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import io.github.ngirchev.aibot.common.model.AssistantRole;
import io.github.ngirchev.aibot.common.service.AssistantRoleService;
import io.github.ngirchev.aibot.rest.model.RestUser;
import io.github.ngirchev.aibot.rest.repository.RestUserRepository;

import java.time.OffsetDateTime;
import java.util.Optional;

@RequiredArgsConstructor
public class RestUserService {
    
    private final RestUserRepository restUserRepository;
    private final AssistantRoleService assistantRoleService;
    
    /**
     * Gets or creates user by email
     */
    @Transactional
    public RestUser getOrCreateUser(String email) {
        return restUserRepository.findByEmail(email)
            .orElseGet(() -> {
                RestUser newUser = new RestUser();
                newUser.setEmail(email);
                newUser.setUsername(email);
                newUser.setCreatedAt(OffsetDateTime.now());
                newUser.setUpdatedAt(OffsetDateTime.now());
                newUser.setLastActivityAt(OffsetDateTime.now());
                return restUserRepository.save(newUser);
            });
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

