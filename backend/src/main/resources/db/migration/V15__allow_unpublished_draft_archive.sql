CREATE OR REPLACE FUNCTION guard_workflow_version_immutability()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'WorkflowVersion % in status % is immutable', OLD.id, OLD.status
                USING ERRCODE = '55000';
        END IF;
        RETURN OLD;
    END IF;

    IF OLD.status = 'DRAFT' AND NEW.status NOT IN ('DRAFT', 'PUBLISHED', 'ARCHIVED') THEN
        RAISE EXCEPTION 'Draft WorkflowVersion has an invalid lifecycle transition'
            USING ERRCODE = '23514';
    ELSIF OLD.status = 'PUBLISHED'
          AND NEW.status NOT IN ('PUBLISHED', 'SUPERSEDED', 'ARCHIVED') THEN
        RAISE EXCEPTION 'Published WorkflowVersion has an invalid lifecycle transition'
            USING ERRCODE = '55000';
    ELSIF OLD.status = 'SUPERSEDED' AND NEW.status NOT IN ('SUPERSEDED', 'ARCHIVED') THEN
        RAISE EXCEPTION 'Superseded WorkflowVersion may only transition to ARCHIVED'
            USING ERRCODE = '55000';
    ELSIF OLD.status = 'ARCHIVED' AND NEW.status <> 'ARCHIVED' THEN
        RAISE EXCEPTION 'Archived WorkflowVersion is immutable'
            USING ERRCODE = '55000';
    END IF;

    IF OLD.status <> 'DRAFT'
       AND (to_jsonb(NEW) - 'status' - 'lock_version')
           IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'lock_version') THEN
        RAISE EXCEPTION 'Published WorkflowVersion % content is immutable', OLD.id
            USING ERRCODE = '55000';
    END IF;

    -- ARCHIVED may also represent a discarded draft that was never executable.
    IF (NEW.status IN ('PUBLISHED', 'SUPERSEDED')
        OR (NEW.status = 'ARCHIVED' AND NEW.published_at IS NOT NULL))
        AND (
            NEW.checksum IS NULL
            OR btrim(NEW.checksum) = ''
            OR NEW.execution_package_json IS NULL
            OR NEW.published_by IS NULL
            OR NEW.published_at IS NULL
        ) THEN
        RAISE EXCEPTION 'Published WorkflowVersion % requires frozen artifact metadata', NEW.id
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END
$$;
