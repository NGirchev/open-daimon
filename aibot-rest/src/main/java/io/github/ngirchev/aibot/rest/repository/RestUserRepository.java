package io.github.ngirchev.aibot.rest.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import io.github.ngirchev.aibot.rest.model.RestUser;

import java.util.Optional;

@Repository
public interface RestUserRepository extends JpaRepository<RestUser, Long> {
    
    Optional<RestUser> findByEmail(String email);
    
    boolean existsByEmail(String email);
} 