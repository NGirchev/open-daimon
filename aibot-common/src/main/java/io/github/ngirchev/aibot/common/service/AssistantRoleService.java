package io.github.ngirchev.aibot.common.service;

import io.github.ngirchev.aibot.common.model.AssistantRole;
import io.github.ngirchev.aibot.common.model.User;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Service for managing assistant roles with versioning.
 */
public interface AssistantRoleService {
    
    /**
     * Gets active role for user.
     *
     * @param user user
     * @return active role or Optional.empty()
     */
    Optional<AssistantRole> getActiveRole(User user);
    
    /**
     * Creates or returns existing role with same content.
     * If role with this content exists, returns it; otherwise creates new version.
     *
     * @param user user
     * @param content role content
     * @return role
     */
    AssistantRole createOrGetRole(User user, String content);
    
    /**
     * Sets role as active for user.
     * Deactivates all other user roles.
     *
     * @param role role to activate
     */
    void setActiveRole(AssistantRole role);
    
    /**
     * Updates user's active role.
     * If content unchanged returns current role; if changed creates new version or activates existing.
     *
     * @param user user
     * @param content new role content
     * @return active role
     */
    AssistantRole updateActiveRole(User user, String content);
    
    /**
     * Increments role usage counter.
     *
     * @param role role
     */
    void incrementUsage(AssistantRole role);
    
    /**
     * Gets all user roles.
     *
     * @param user user
     * @return list of roles sorted by version (newest first)
     */
    List<AssistantRole> getAllUserRoles(User user);
    
    /**
     * Gets role by version.
     *
     * @param user user
     * @param version role version
     * @return role or Optional.empty()
     */
    Optional<AssistantRole> getRoleByVersion(User user, Integer version);
    
    /**
     * Removes unused roles (inactive, no requests, older than threshold).
     *
     * @param thresholdDate roles older than this will be deleted
     * @return number of deleted roles
     */
    int cleanupUnusedRoles(OffsetDateTime thresholdDate);
    
    /**
     * Gets list of unused roles.
     *
     * @param thresholdDate roles older than this are considered unused
     * @return list of unused roles
     */
    List<AssistantRole> findUnusedRoles(OffsetDateTime thresholdDate);
    
    /**
     * Gets default role for user.
     * If user has no active role, creates one with default content.
     *
     * @param user user
     * @param defaultContent default role content
     * @return active role
     */
    AssistantRole getOrCreateDefaultRole(User user, String defaultContent);
    
    /**
     * Gets role by ID.
     *
     * @param roleId role ID
     * @return role or Optional.empty()
     */
    Optional<AssistantRole> findById(Long roleId);
}

