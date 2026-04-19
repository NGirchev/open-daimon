package io.github.ngirchev.opendaimon.rest.repository;

import io.github.ngirchev.opendaimon.common.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Polymorphic lookup over the base User table for admin filter dropdowns.
 * Returns TelegramUser / RestUser subclasses transparently thanks to JOINED inheritance.
 */
@Repository
public interface AdminUserRepository extends JpaRepository<User, Long> {

    /**
     * Case-insensitive search by username / first_name / last_name.
     * If {@code search} is blank, returns all users paginated.
     */
    @Query("SELECT u FROM User u " +
            "WHERE (:search IS NULL OR :search = '' " +
            "       OR LOWER(u.username) LIKE LOWER(CONCAT('%', :search, '%')) " +
            "       OR LOWER(u.firstName) LIKE LOWER(CONCAT('%', :search, '%')) " +
            "       OR LOWER(u.lastName) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<User> searchAll(@Param("search") String search, Pageable pageable);
}
