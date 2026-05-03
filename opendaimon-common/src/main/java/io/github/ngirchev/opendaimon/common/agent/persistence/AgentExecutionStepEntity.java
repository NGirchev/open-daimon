package io.github.ngirchev.opendaimon.common.agent.persistence;

import io.github.ngirchev.opendaimon.common.model.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Persisted record of a single orchestration step execution.
 */
@Entity
@Table(name = "agent_execution_step")
@Getter
@Setter
@NoArgsConstructor
public class AgentExecutionStepEntity extends AbstractEntity<Long> {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_id", nullable = false)
    private AgentExecutionEntity execution;

    @Column(name = "step_id", nullable = false)
    private String stepId;

    @Column(name = "step_name", nullable = false)
    private String stepName;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String task;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StepStatus status;

    @Column(columnDefinition = "TEXT")
    private String output;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "iterations_used", nullable = false)
    private int iterationsUsed;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    public enum StepStatus {
        RUNNING, COMPLETED, FAILED, SKIPPED
    }
}
