package io.github.ngirchev.aibot.bulkhead.service.impl;

import io.github.ngirchev.aibot.bulkhead.model.UserPriority;
import io.github.ngirchev.aibot.bulkhead.service.IUserPriorityService;

/**
 * NoOp implementation of IUserPriorityService.
 * Used when bulkhead is disabled (ai-bot.common.bulkhead.enabled=false).
 * Always returns REGULAR priority for any user.
 */
public class NoOpUserPriorityService implements IUserPriorityService {

    @Override
    public UserPriority getUserPriority(Long userId) {
        return UserPriority.REGULAR;
    }
}
