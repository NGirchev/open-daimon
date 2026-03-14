package io.github.ngirchev.opendaimon.bulkhead.service;

import java.util.Optional;

public interface IUserService {
    Optional<IUserObject> findById(Long userId);
}
