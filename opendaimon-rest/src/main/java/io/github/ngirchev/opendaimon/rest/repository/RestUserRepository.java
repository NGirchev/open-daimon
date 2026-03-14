package io.github.ngirchev.opendaimon.rest.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import io.github.ngirchev.opendaimon.rest.model.RestUser;

import java.util.Optional;

@Repository
public interface RestUserRepository extends JpaRepository<RestUser, Long> {
    
    Optional<RestUser> findByEmail(String email);
    
    boolean existsByEmail(String email);
} 