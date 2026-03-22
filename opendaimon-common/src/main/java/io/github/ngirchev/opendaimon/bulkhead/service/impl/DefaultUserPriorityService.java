package io.github.ngirchev.opendaimon.bulkhead.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.bulkhead.service.IUserObject;
import io.github.ngirchev.opendaimon.bulkhead.service.IUserPriorityService;
import io.github.ngirchev.opendaimon.bulkhead.service.IUserService;
import io.github.ngirchev.opendaimon.bulkhead.service.IWhitelistService;


/**
 * Implementation of user priority service.
 * Determines priority from Telegram user data.
 */
@Slf4j
@RequiredArgsConstructor
public class DefaultUserPriorityService implements IUserPriorityService {

    private final IUserService userService;
    private final IWhitelistService whitelistService;

    /**
     * Determines user priority by user id.
     * Admins always get ADMIN priority regardless of other conditions.
     * If user is blocked in Telegram - returns BLOCKED.
     * If user has Telegram Premium or is a member of a configured whitelist channel/group - returns VIP.
     * Otherwise - returns REGULAR.
     *
     * @param userId user identifier
     * @return user priority (ADMIN, VIP, REGULAR or BLOCKED)
     */
    @Override
    public UserPriority getUserPriority(Long userId) {
        if (userId == null) {
            return UserPriority.BLOCKED;
        }

        var user = userService.findById(userId);
        
        // Admins always get ADMIN priority regardless of other conditions
        if (user.map(IUserObject::getIsAdmin).map(Boolean.TRUE::equals).orElse(false)) {
            return UserPriority.ADMIN;
        }

        // Check if user is in whitelist
        if (!whitelistService.isUserAllowed(userId)) {
            // If not in whitelist, check channel membership
            if (whitelistService.checkUserInChannel(userId)) {
                // If user is in channel, add to whitelist
                whitelistService.addToWhitelist(userId);
            } else {
                return UserPriority.BLOCKED;
            }
        }

        if (user.map(IUserObject::getIsBlocked).map(Boolean.TRUE::equals).orElse(false)) {
            return UserPriority.BLOCKED;
        }
        if (user.map(IUserObject::getIsPremium).map(Boolean.TRUE::equals).orElse(false)) {
            return UserPriority.VIP;
        }
        if (isUserInConfiguredChannel(userId)) {
            return UserPriority.VIP;
        }
        return UserPriority.REGULAR;
    }

    /**
     * Returns true if the user is a member of one of the configured whitelist channels/groups.
     * Used to grant Premium (VIP) priority to channel members.
     */
    private boolean isUserInConfiguredChannel(Long userId) {
        try {
            return whitelistService.checkUserInChannel(userId);
        } catch (Exception e) {
            log.debug("Could not check channel membership for user {}: {}", userId, e.getMessage());
            return false;
        }
    }
} 