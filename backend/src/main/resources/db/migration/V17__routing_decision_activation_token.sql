-- V17: Routing Decision + Activation Token Persistence
-- Persists every routing evaluation and every activation token before node execution.
-- Activation tokens are the durable correlation source for crash-recovery and explainability.
-- UNIQUE (activation_key) ensures no duplicate downstream execution even on retry.

-- ─────────────────────────────────────────────────────────────────
-- 1. ROUTING DECISIONS
-- ─────────────────────────────────────────────────────────────────
CREATE TABLE routing_decisions (
    id                      UUID PRIMARY KEY,
    event_id                UUID NOT NULL,
    source_node_execution_id UUID NOT NULL,
    outcome_port            VARCHAR(128) NOT NULL,
    routing_mode            VARCHAR(64) NOT NULL,
    evaluated_edges_json    JSONB NOT NULL,
    selected_edge_ids_json  JSONB NOT NULL,
    decided_at              TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_routing_decisions_event
        FOREIGN KEY (event_id)
        REFERENCES events (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_routing_decisions_source
        FOREIGN KEY (source_node_execution_id)
        REFERENCES node_executions (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_routing_decisions_mode
        CHECK (routing_mode IN (
            'NONE', 'SINGLE_BY_PORT', 'EXCLUSIVE_CONDITIONAL',
            'ALL_OUTGOING', 'ALL_MATCHING'
        )),
    CONSTRAINT ck_routing_decisions_evaluated_json
        CHECK (jsonb_typeof(evaluated_edges_json) = 'array'),
    CONSTRAINT ck_routing_decisions_selected_json
        CHECK (jsonb_typeof(selected_edge_ids_json) = 'array'),
    CONSTRAINT ck_routing_decisions_timestamp
        CHECK (decided_at IS NOT NULL)
);

-- One routing decision per source node execution
CREATE UNIQUE INDEX uq_routing_decisions_source
    ON routing_decisions (source_node_execution_id);

CREATE INDEX ix_routing_decisions_event
    ON routing_decisions (event_id, decided_at);

-- ─────────────────────────────────────────────────────────────────
-- 2. ACTIVATION TOKENS
-- ─────────────────────────────────────────────────────────────────
CREATE TABLE activation_tokens (
    id                      UUID PRIMARY KEY,
    routing_decision_id     UUID NOT NULL,
    event_id                UUID NOT NULL,
    source_node_execution_id UUID NOT NULL,
    edge_id                 UUID NOT NULL,
    target_node_definition_id UUID NOT NULL,
    activation_key          VARCHAR(512) NOT NULL,
    path_token              VARCHAR(256) NOT NULL,
    cycle_id                UUID NOT NULL,
    item_token              VARCHAR(256),
    split_scope_id          UUID,
    join_scope_id           UUID,
    status                  VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at              TIMESTAMPTZ NOT NULL,
    activated_at            TIMESTAMPTZ,

    CONSTRAINT fk_activation_tokens_routing_decision
        FOREIGN KEY (routing_decision_id)
        REFERENCES routing_decisions (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_activation_tokens_event
        FOREIGN KEY (event_id)
        REFERENCES events (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_activation_tokens_source
        FOREIGN KEY (source_node_execution_id)
        REFERENCES node_executions (id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_activation_tokens_key
        UNIQUE (activation_key),
    CONSTRAINT ck_activation_tokens_status
        CHECK (status IN ('PENDING', 'ACTIVATED', 'SKIPPED', 'CANCELLED')),
    CONSTRAINT ck_activation_tokens_path_token
        CHECK (btrim(path_token) <> ''),
    CONSTRAINT ck_activation_tokens_activation_key
        CHECK (btrim(activation_key) <> ''),
    CONSTRAINT ck_activation_tokens_timestamps
        CHECK (activated_at IS NULL OR activated_at >= created_at)
);

CREATE INDEX ix_activation_tokens_routing_decision
    ON activation_tokens (routing_decision_id);
CREATE INDEX ix_activation_tokens_event_status
    ON activation_tokens (event_id, status, created_at);
CREATE INDEX ix_activation_tokens_source
    ON activation_tokens (source_node_execution_id);

-- Guard: activation_token status is append-progressive (PENDING → ACTIVATED/SKIPPED/CANCELLED)
CREATE OR REPLACE FUNCTION guard_activation_token_status()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'ActivationToken cannot be hard-deleted'
            USING ERRCODE = '55000';
    END IF;
    IF OLD.status IN ('ACTIVATED', 'SKIPPED', 'CANCELLED') THEN
        RAISE EXCEPTION 'Terminal ActivationToken cannot be mutated'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_activation_tokens_guard_status
BEFORE UPDATE OR DELETE ON activation_tokens
FOR EACH ROW EXECUTE FUNCTION guard_activation_token_status();
