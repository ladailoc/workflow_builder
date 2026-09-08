-- Assignment changes are commands, not mutable task attributes. The append-only history row must
-- be written first in the same database transaction so a reassignment can never be silent.
CREATE OR REPLACE FUNCTION guard_task_execution_history()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    expected_action VARCHAR(32);
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'TaskExecution history cannot be hard-deleted'
            USING ERRCODE = '55000';
    END IF;

    IF OLD.status IN ('COMPLETED', 'CANCELLED', 'EXPIRED') THEN
        RAISE EXCEPTION 'Terminal TaskExecution cannot be revived or mutated'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.node_execution_id <> OLD.node_execution_id
        OR NEW.item_execution_id IS DISTINCT FROM OLD.item_execution_id
        OR NEW.title_snapshot <> OLD.title_snapshot
        OR NEW.description_snapshot IS DISTINCT FROM OLD.description_snapshot
        OR NEW.form_schema_json IS DISTINCT FROM OLD.form_schema_json
        OR NEW.input_snapshot_json IS DISTINCT FROM OLD.input_snapshot_json
        OR NEW.priority <> OLD.priority
        OR NEW.due_at IS DISTINCT FROM OLD.due_at
        OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'TaskExecution identity and activation snapshots are immutable'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.assignee_id IS DISTINCT FROM OLD.assignee_id THEN
        expected_action := CASE
            WHEN OLD.assignee_id IS NULL AND NEW.status = 'CLAIMED' THEN 'CLAIM'
            WHEN OLD.assignee_id IS NULL THEN 'ASSIGN'
            WHEN NEW.assignee_id IS NULL THEN 'UNCLAIM'
            ELSE 'REASSIGN'
        END;

        IF NOT EXISTS (
            SELECT 1
              FROM task_assignment_history history
             WHERE history.task_id = OLD.id
               AND history.action_type = expected_action
               AND history.from_user_id IS NOT DISTINCT FROM OLD.assignee_id
               AND history.to_user_id IS NOT DISTINCT FROM NEW.assignee_id
               AND history.xmin = pg_current_xact_id()::text::xid
        ) THEN
            RAISE EXCEPTION 'Task assignee change requires append-only history in the same command'
                USING ERRCODE = '55000';
        END IF;
    END IF;

    RETURN NEW;
END
$$;
