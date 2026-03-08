package io.github.ngirchev.aibot.bulkhead.service;

import java.util.Optional;

public interface IUserService {
    Optional<? extends IUserObject> findById(Long userId);
}
