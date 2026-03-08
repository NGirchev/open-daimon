package io.github.ngirchev.aibot.common.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import io.github.ngirchev.aibot.common.model.AssistantRole;
import io.github.ngirchev.aibot.common.model.User;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AssistantRoleRepository extends JpaRepository<AssistantRole, Long> {
    
    /**
     * Finds active role for user.
     */
    @Query("SELECT ar FROM AssistantRole ar WHERE ar.user = :user AND ar.isActive = true")
    Optional<AssistantRole> findActiveByUser(@Param("user") User user);
    
    /**
     * Finds all user roles sorted by version.
     */
    @Query("SELECT ar FROM AssistantRole ar WHERE ar.user = :user ORDER BY ar.version DESC")
    List<AssistantRole> findAllByUserOrderByVersionDesc(@Param("user") User user);
    
    /**
     * Finds role by user and version.
     */
    @Query("SELECT ar FROM AssistantRole ar WHERE ar.user = :user AND ar.version = :version")
    Optional<AssistantRole> findByUserAndVersion(@Param("user") User user, @Param("version") Integer version);
    
    /**
     * Finds role by user and content hash.
     */
    @Query("SELECT ar FROM AssistantRole ar WHERE ar.user = :user AND ar.contentHash = :contentHash")
    Optional<AssistantRole> findByUserAndContentHash(@Param("user") User user, @Param("contentHash") String contentHash);
    
    /**
     * Gets max role version for user.
     */
    @Query("SELECT COALESCE(MAX(ar.version), 0) FROM AssistantRole ar WHERE ar.user = :user")
    Integer findMaxVersionByUser(@Param("user") User user);
    
    /**
     * Deactivates all user roles.
     */
    @Modifying
    @Query("UPDATE AssistantRole ar SET ar.isActive = false WHERE ar.user = :user")
    void deactivateAllByUser(@Param("user") User user);
    
    /**
     * Finds unused roles (inactive and no requests in given period).
     */
    @Query("SELECT ar FROM AssistantRole ar WHERE ar.isActive = false " +
           "AND ar.usageCount = 0 " +
           "AND ar.lastUsedAt < :thresholdDate")
    List<AssistantRole> findUnusedRoles(@Param("thresholdDate") OffsetDateTime thresholdDate);
    
    /**
     * Finds low-usage roles.
     */
    @Query("SELECT ar FROM AssistantRole ar WHERE ar.isActive = false " +
           "AND ar.usageCount > 0 " +
           "AND ar.lastUsedAt < :thresholdDate " +
           "ORDER BY ar.lastUsedAt ASC")
    List<AssistantRole> findLowUsageRoles(@Param("thresholdDate") OffsetDateTime thresholdDate);
}

