-- Agent execution persistence for orchestration layer
CREATE TABLE agent_execution (
    id              BIGSERIAL PRIMARY KEY,
    plan_name       VARCHAR(255) NOT NULL,
    conversation_id VARCHAR(255),
    status          VARCHAR(50) NOT NULL,
    total_steps     INT NOT NULL DEFAULT 0,
    completed_steps INT NOT NULL DEFAULT 0,
    failed_steps    INT NOT NULL DEFAULT 0,
    final_output    TEXT,
    error_message   TEXT,
    started_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at     TIMESTAMP,
    duration_ms     BIGINT
);

CREATE TABLE agent_execution_step (
    id              BIGSERIAL PRIMARY KEY,
    execution_id    BIGINT NOT NULL REFERENCES agent_execution(id) ON DELETE CASCADE,
    step_id         VARCHAR(255) NOT NULL,
    step_name       VARCHAR(255) NOT NULL,
    task            TEXT NOT NULL,
    status          VARCHAR(50) NOT NULL,
    output          TEXT,
    error_message   TEXT,
    iterations_used INT NOT NULL DEFAULT 0,
    started_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at     TIMESTAMP,
    duration_ms     BIGINT
);

CREATE INDEX idx_agent_execution_conversation ON agent_execution(conversation_id);
CREATE INDEX idx_agent_execution_status ON agent_execution(status);
CREATE INDEX idx_agent_execution_step_execution ON agent_execution_step(execution_id);
