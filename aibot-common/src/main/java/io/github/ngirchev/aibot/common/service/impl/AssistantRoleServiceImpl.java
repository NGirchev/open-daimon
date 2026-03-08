package io.github.ngirchev.aibot.common.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import io.github.ngirchev.aibot.common.model.AssistantRole;
import io.github.ngirchev.aibot.common.model.User;
import io.github.ngirchev.aibot.common.repository.AssistantRoleRepository;
import io.github.ngirchev.aibot.common.service.AssistantRoleService;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Implementation of assistant role management service.
 */
@Slf4j
@RequiredArgsConstructor
public class AssistantRoleServiceImpl implements AssistantRoleService {
    
    private final AssistantRoleRepository assistantRoleRepository;
    
    @Override
    @Transactional(readOnly = true)
    public Optional<AssistantRole> getActiveRole(User user) {
        log.debug("Getting active role for user: {}", user.getId());
        return assistantRoleRepository.findActiveByUser(user);
    }
    
    @Override
    @Transactional
    public AssistantRole createOrGetRole(User user, String content) {
        log.debug("Creating or getting role for user: {}", user.getId());
        
        // Compute content hash
        String contentHash = String.valueOf(content.hashCode());
        
        // Check if role with this content already exists
        Optional<AssistantRole> existingRole = assistantRoleRepository.findByUserAndContentHash(user, contentHash);
        
        if (existingRole.isPresent()) {
            log.debug("Found existing role with same content for user: {}", user.getId());
            return existingRole.get();
        }
        
        // Create new role
        AssistantRole role = new AssistantRole();
        role.setUser(user);
        role.setContent(content);
        role.setContentHash(contentHash);
        
        // Get next version
        Integer nextVersion = assistantRoleRepository.findMaxVersionByUser(user) + 1;
        role.setVersion(nextVersion);
        role.setIsActive(false);
        
        log.info("Creating new role version {} for user: {}", nextVersion, user.getId());
        return assistantRoleRepository.save(role);
    }
    
    @Override
    @Transactional
    public void setActiveRole(AssistantRole role) {
        log.debug("Setting active role {}", role.getId());
        
        // Reload from DB to avoid LazyInitializationException
        AssistantRole managedRole = assistantRoleRepository.findById(role.getId())
                .orElseThrow(() -> new IllegalArgumentException("Role not found with id: " + role.getId()));
        
        // Deactivate all user roles
        assistantRoleRepository.deactivateAllByUser(managedRole.getUser());
        
        // Activate the specified role
        managedRole.setIsActive(true);
        assistantRoleRepository.save(managedRole);
        
        log.info("Role {} set as active for user: {}", managedRole.getId(), managedRole.getUser().getId());
    }
    
    @Override
    @Transactional
    public AssistantRole updateActiveRole(User user, String content) {
        log.debug("Updating active role for user: {}", user.getId());
        
        // Get current active role
        Optional<AssistantRole> currentActiveRole = getActiveRole(user);
        
        // If content unchanged, return current role
        if (currentActiveRole.isPresent() && currentActiveRole.get().getContent().equals(content)) {
            log.debug("Role content unchanged for user: {}", user.getId());
            return currentActiveRole.get();
        }
        
        // Create or get role with new content
        AssistantRole role = createOrGetRole(user, content);
        
        // Activate new role
        setActiveRole(role);
        
        return role;
    }
    
    @Override
    @Transactional
    public void incrementUsage(AssistantRole role) {
        log.debug("Incrementing usage count for role: {}", role.getId());
        // Reload from DB to avoid LazyInitializationException
        // if role was loaded in another transaction or is a lazy proxy
        AssistantRole managedRole = assistantRoleRepository.findById(role.getId())
                .orElseThrow(() -> new IllegalArgumentException("Role not found with id: " + role.getId()));
        managedRole.incrementUsageCount();
        assistantRoleRepository.save(managedRole);
    }
    
    @Override
    @Transactional(readOnly = true)
    public List<AssistantRole> getAllUserRoles(User user) {
        log.debug("Getting all roles for user: {}", user.getId());
        return assistantRoleRepository.findAllByUserOrderByVersionDesc(user);
    }
    
    @Override
    @Transactional(readOnly = true)
    public Optional<AssistantRole> getRoleByVersion(User user, Integer version) {
        log.debug("Getting role version {} for user: {}", version, user.getId());
        return assistantRoleRepository.findByUserAndVersion(user, version);
    }
    
    @Override
    @Transactional
    public int cleanupUnusedRoles(OffsetDateTime thresholdDate) {
        log.info("Starting cleanup of unused roles older than: {}", thresholdDate);
        
        List<AssistantRole> unusedRoles = assistantRoleRepository.findUnusedRoles(thresholdDate);
        
        if (!unusedRoles.isEmpty()) {
            assistantRoleRepository.deleteAll(unusedRoles);
            log.info("Deleted {} unused roles", unusedRoles.size());
        }
        
        return unusedRoles.size();
    }
    
    @Override
    @Transactional(readOnly = true)
    public List<AssistantRole> findUnusedRoles(OffsetDateTime thresholdDate) {
        log.debug("Finding unused roles older than: {}", thresholdDate);
        return assistantRoleRepository.findUnusedRoles(thresholdDate);
    }
    
    @Override
    @Transactional
    public AssistantRole getOrCreateDefaultRole(User user, String defaultContent) {
        log.debug("Getting or creating default role for user: {}", user.getId());
        
        // Check if there is an active role
        Optional<AssistantRole> activeRole = getActiveRole(user);
        
        if (activeRole.isPresent()) {
            return activeRole.get();
        }
        
        // Create role with default content
        AssistantRole role = createOrGetRole(user, defaultContent);
        setActiveRole(role);
        
        log.info("Created default role for user: {}", user.getId());
        return role;
    }
    
    @Override
    @Transactional(readOnly = true)
    public Optional<AssistantRole> findById(Long roleId) {
        log.debug("Finding role by id: {}", roleId);
        return assistantRoleRepository.findById(roleId);
    }
}

