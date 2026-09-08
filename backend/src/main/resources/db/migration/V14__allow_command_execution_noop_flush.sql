CREATE OR REPLACE FUNCTION guard_command_execution_history()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'CommandExecution history cannot be hard-deleted'
            USING ERRCODE = '55000';
    END IF;

    -- Hibernate may issue a no-op update for JSON-backed fields during the final flush.
    -- It does not mutate command history and must not make a successful command fail.
    IF NEW IS NOT DISTINCT FROM OLD THEN
        RETURN NEW;
    END IF;

    IF OLD.status IN ('SUCCEEDED', 'FAILED') THEN
        RAISE EXCEPTION 'Terminal CommandExecution is immutable'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.scope_type <> OLD.scope_type
        OR NEW.scope_id <> OLD.scope_id
        OR NEW.command_id <> OLD.command_id
        OR NEW.command_type <> OLD.command_type
        OR NEW.actor_id <> OLD.actor_id
        OR NEW.expected_version IS DISTINCT FROM OLD.expected_version
        OR NEW.request_hash <> OLD.request_hash
        OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'CommandExecution identity and request are immutable'
            USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END
$$;
