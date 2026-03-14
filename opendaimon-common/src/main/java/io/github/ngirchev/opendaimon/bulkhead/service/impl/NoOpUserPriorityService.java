package io.github.ngirchev.opendaimon.bulkhead.service.impl;

import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.bulkhead.service.IUserPriorityService;

/**
 * NoOp implementation of IUserPriorityService.
 * Used when bulkhead is disabled (open-daimon.common.bulkhead.enabled=false).
 * Always returns REGULAR priority for any user.
 */
public class NoOpUserPriorityService implements IUserPriorityService {

    @Override
    public UserPriority getUserPriority(Long userId) {
        return UserPriority.REGULAR;
    }
}
