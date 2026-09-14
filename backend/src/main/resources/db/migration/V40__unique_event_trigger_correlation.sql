-- P2-25 / workflow_spec.md §3.3: a non-null trigger correlation key identifies one
-- logical Event within its trigger type, including after the Event becomes terminal.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM events
        WHERE trigger_correlation_key IS NOT NULL
        GROUP BY trigger_type, trigger_correlation_key
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION
            'Cannot enforce event trigger correlation uniqueness: duplicate logical triggers exist';
    END IF;
END
$$;

CREATE UNIQUE INDEX uq_events_trigger_correlation
    ON events (trigger_type, trigger_correlation_key)
    WHERE trigger_correlation_key IS NOT NULL;
