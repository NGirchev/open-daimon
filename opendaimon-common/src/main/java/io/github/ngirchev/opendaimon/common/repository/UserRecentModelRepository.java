package io.github.ngirchev.opendaimon.common.repository;

import io.github.ngirchev.opendaimon.common.model.UserRecentModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRecentModelRepository extends JpaRepository<UserRecentModel, Long> {

    /**
     * Looks up an existing recent-model record for (user, modelName).
     */
    Optional<UserRecentModel> findByUserIdAndModelName(Long userId, String modelName);

    /**
     * Returns up to the top-N most recently used models for the given user,
     * ordered by {@code lastUsedAt DESC}.
     */
    @Query("SELECT r FROM UserRecentModel r " +
           "WHERE r.user.id = :userId " +
           "ORDER BY r.lastUsedAt DESC")
    List<UserRecentModel> findTopByUser(@Param("userId") Long userId,
                                        org.springframework.data.domain.Pageable pageable);

    /**
     * Deletes all entries for the user whose id is not in the given retain list.
     * Used to prune history after an upsert so that only the top-N records remain.
     */
    @Modifying
    @Query("DELETE FROM UserRecentModel r " +
           "WHERE r.user.id = :userId AND r.id NOT IN :retainIds")
    int deleteByUserIdAndIdNotIn(@Param("userId") Long userId,
                                 @Param("retainIds") List<Long> retainIds);
}
