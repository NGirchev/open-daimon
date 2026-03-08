package io.github.ngirchev.aibot.bulkhead.model;

/**
 * Enumeration of user priorities.
 */
public enum UserPriority {
    /**
     * Admins — separate priority with maximum access.
     */
    ADMIN,

    /**
     * VIP users (paid) — have access to priority resources.
     */
    VIP,
    
    /**
     * Regular users (free) — have access to limited resources.
     */
    REGULAR,
    
    /**
     * Blocked users (not paid) — access denied.
     */
    BLOCKED
}