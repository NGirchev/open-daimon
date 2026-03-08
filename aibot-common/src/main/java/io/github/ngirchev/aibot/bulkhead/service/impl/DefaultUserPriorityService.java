package io.github.ngirchev.aibot.bulkhead.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.github.ngirchev.aibot.bulkhead.model.UserPriority;
import io.github.ngirchev.aibot.bulkhead.service.IUserObject;
import io.github.ngirchev.aibot.bulkhead.service.IUserPriorityService;
import io.github.ngirchev.aibot.bulkhead.service.IUserService;
import io.github.ngirchev.aibot.bulkhead.service.IWhitelistService;


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
     * If user has premium status - returns VIP.
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
        } else if (user.map(IUserObject::getIsPremium).map(Boolean.TRUE::equals).orElse(false)) {
            return UserPriority.VIP;
        } else {
            return UserPriority.REGULAR;
        }
    }
} 