package io.github.ngirchev.opendaimon.bulkhead.service;

import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;

/**
 * Interface for resolving user priority.
 * Isolated from chat business logic and reusable in other services.
 */
public interface IUserPriorityService {
    
    /**
     * Resolves user priority by user id.
     *
     * @param userId user identifier
     * @return user priority (ADMIN, VIP, REGULAR or BLOCKED)
     */
    UserPriority getUserPriority(Long userId);
}