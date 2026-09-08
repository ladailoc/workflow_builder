-- Event FAILED is terminal. Retry creates a new execution attempt/occurrence; it never revives Event history.
CREATE OR REPLACE FUNCTION guard_runtime_history()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Runtime history cannot be hard-deleted'
            USING ERRCODE = '55000';
    END IF;

    IF (
        TG_TABLE_NAME = 'events'
        AND OLD.status IN ('COMPLETED', 'FAILED', 'CANCELLED', 'TERMINATED')
    ) OR (
        TG_TABLE_NAME = 'node_executions'
        AND OLD.status IN ('COMPLETED', 'FAILED', 'CANCELLED', 'SKIPPED')
    ) THEN
        RAISE EXCEPTION 'Terminal runtime history cannot be revived or mutated'
            USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END
$$;
