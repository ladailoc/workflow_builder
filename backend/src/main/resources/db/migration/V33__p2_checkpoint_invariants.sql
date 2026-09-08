-- P2 architecture checkpoint hardening: exact connector pinning, idempotency,
-- optimistic locking, immutable contracts, and history-preserving delete rules.

ALTER TABLE integration_executions
    ADD COLUMN connector_action_version_id UUID,
    ADD COLUMN lock_version BIGINT NOT NULL DEFAULT 0;

UPDATE integration_executions ie
SET connector_action_version_id = cav.id
FROM connector_definitions cd
JOIN connector_actions ca ON ca.connector_id = cd.id
JOIN connector_action_versions cav ON cav.connector_action_id = ca.id
WHERE cd.key = ie.connector_key
  AND ca.action_key = ie.action_key
  AND cav.version_no = ie.action_version;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM integration_executions
        WHERE connector_action_version_id IS NULL
    ) THEN
        RAISE EXCEPTION 'Cannot pin existing integration execution to an exact connector action version';
    END IF;
END;
$$;

ALTER TABLE integration_executions
    ALTER COLUMN connector_action_version_id SET NOT NULL,
    ADD CONSTRAINT fk_integration_execution_action_version
        FOREIGN KEY (connector_action_version_id)
        REFERENCES connector_action_versions(id) ON DELETE RESTRICT,
    ADD CONSTRAINT uq_integration_execution_idempotency_key UNIQUE (idempotency_key);

CREATE UNIQUE INDEX uq_integration_execution_callback_correlation
    ON integration_executions(callback_correlation_id)
    WHERE callback_correlation_id IS NOT NULL;

DROP INDEX IF EXISTS idx_integration_executions_idempotency_key;
DROP INDEX IF EXISTS idx_integration_executions_callback_corr_id;

ALTER TABLE integration_attempts
    DROP CONSTRAINT integration_attempts_integration_execution_id_fkey,
    ADD CONSTRAINT integration_attempts_integration_execution_id_fkey
        FOREIGN KEY (integration_execution_id)
        REFERENCES integration_executions(id) ON DELETE RESTRICT;

ALTER TABLE sub_workflow_executions
    ADD COLUMN lock_version BIGINT NOT NULL DEFAULT 0,
    DROP CONSTRAINT sub_workflow_executions_parent_event_id_fkey,
    DROP CONSTRAINT sub_workflow_executions_parent_node_execution_id_fkey,
    DROP CONSTRAINT sub_workflow_executions_child_event_id_fkey,
    ADD CONSTRAINT sub_workflow_executions_parent_event_id_fkey
        FOREIGN KEY (parent_event_id) REFERENCES events(id) ON DELETE RESTRICT,
    ADD CONSTRAINT sub_workflow_executions_parent_node_execution_id_fkey
        FOREIGN KEY (parent_node_execution_id) REFERENCES node_executions(id) ON DELETE RESTRICT,
    ADD CONSTRAINT sub_workflow_executions_child_event_id_fkey
        FOREIGN KEY (child_event_id) REFERENCES events(id) ON DELETE RESTRICT;

CREATE OR REPLACE FUNCTION trg_guard_connector_action_version_immutable()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status IN ('PUBLISHED', 'DEPRECATED') THEN
            RAISE EXCEPTION 'A published or deprecated connector action version is immutable and cannot be deleted';
        END IF;
        RETURN OLD;
    END IF;

    IF OLD.status IN ('PUBLISHED', 'DEPRECATED') THEN
        IF NEW.connector_action_id IS DISTINCT FROM OLD.connector_action_id
            OR NEW.version_no IS DISTINCT FROM OLD.version_no
            OR NEW.input_schema_json IS DISTINCT FROM OLD.input_schema_json
            OR NEW.output_schema_json IS DISTINCT FROM OLD.output_schema_json
            OR NEW.execution_config_json IS DISTINCT FROM OLD.execution_config_json
            OR NEW.retry_policy_json IS DISTINCT FROM OLD.retry_policy_json
            OR NEW.idempotency_policy_json IS DISTINCT FROM OLD.idempotency_policy_json
            OR NEW.error_mapping_json IS DISTINCT FROM OLD.error_mapping_json
            OR NEW.permission_policy_json IS DISTINCT FROM OLD.permission_policy_json
            OR NEW.created_at IS DISTINCT FROM OLD.created_at
            OR (OLD.status = 'DEPRECATED' AND NEW.status <> 'DEPRECATED')
            OR NEW.status NOT IN ('PUBLISHED', 'DEPRECATED') THEN
            RAISE EXCEPTION 'A published or deprecated connector action version is immutable and cannot be updated';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_connector_action_version_immutability
    ON connector_action_versions;
CREATE TRIGGER trg_connector_action_version_immutability
    BEFORE UPDATE OR DELETE ON connector_action_versions
    FOR EACH ROW EXECUTE FUNCTION trg_guard_connector_action_version_immutable();

CREATE OR REPLACE FUNCTION guard_terminal_integration_attempt_history()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Integration attempt history is append-only';
    END IF;
    IF OLD.status IN ('SUCCESS', 'FAILURE') THEN
        RAISE EXCEPTION 'Terminal integration attempt history is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_integration_attempt_history
    BEFORE UPDATE OR DELETE ON integration_attempts
    FOR EACH ROW EXECUTE FUNCTION guard_terminal_integration_attempt_history();

CREATE TRIGGER trg_integration_callbacks_append_only
    BEFORE UPDATE OR DELETE ON integration_callbacks
    FOR EACH ROW EXECUTE FUNCTION guard_append_only_task_history();
