package io.github.ngirchev.aibot.rest.service;

import io.github.ngirchev.aibot.bulkhead.model.UserPriority;
import io.github.ngirchev.aibot.bulkhead.service.IUserPriorityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;


@Slf4j
@RequiredArgsConstructor
public class RestUserPriorityService implements IUserPriorityService {

    private final IUserPriorityService delegate;

    @Override
    public UserPriority getUserPriority(Long userId) {
        return delegate.getUserPriority(userId);
    }
}
