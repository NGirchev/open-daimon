package io.github.ngirchev.opendaimon.rest.repository;

import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.model.ThreadScopeKind;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Admin-scope queries over ConversationThread.
 * Lives in the rest module on purpose: admin filtering/pagination is not part of the
 * core common contract — this keeps opendaimon-common untouched while still reusing
 * the ConversationThread entity.
 */
@Repository
public interface AdminConversationRepository extends JpaRepository<ConversationThread, Long> {

    /**
     * Paginated lookup with optional filters. Null params disable that filter.
     * JOIN FETCH on user avoids N+1 when the admin UI renders user columns for every row.
     */
    @Query(value = "SELECT t FROM ConversationThread t JOIN FETCH t.user u " +
            "WHERE (:userId IS NULL OR u.id = :userId) " +
            "AND (:scopeKind IS NULL OR t.scopeKind = :scopeKind) " +
            "AND (:isActive IS NULL OR t.isActive = :isActive)",
            countQuery = "SELECT COUNT(t) FROM ConversationThread t " +
                    "WHERE (:userId IS NULL OR t.user.id = :userId) " +
                    "AND (:scopeKind IS NULL OR t.scopeKind = :scopeKind) " +
                    "AND (:isActive IS NULL OR t.isActive = :isActive)")
    Page<ConversationThread> findAllWithFilters(
            @Param("userId") Long userId,
            @Param("scopeKind") ThreadScopeKind scopeKind,
            @Param("isActive") Boolean isActive,
            Pageable pageable);

    /**
     * Detail lookup that eagerly fetches the owner to avoid LazyInitializationException
     * when the admin detail view serializes UserSummaryDto after the transaction closes.
     */
    @Query("SELECT t FROM ConversationThread t JOIN FETCH t.user WHERE t.id = :id")
    java.util.Optional<ConversationThread> findByIdWithUser(@Param("id") Long id);
}
