package io.github.ngirchev.opendaimon.common.agent.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for agent execution persistence.
 */
@Repository
public interface AgentExecutionRepository extends JpaRepository<AgentExecutionEntity, Long> {

    List<AgentExecutionEntity> findByConversationIdOrderByStartedAtDesc(String conversationId);

    List<AgentExecutionEntity> findByStatusOrderByStartedAtDesc(AgentExecutionEntity.ExecutionStatus status);
}
