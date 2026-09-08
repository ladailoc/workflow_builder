-- Platform baseline only. Domain tables are intentionally deferred to later capabilities.
-- No extension is enabled until a concrete schema capability requires one.

DO $$
DECLARE
    database_major_version integer;
BEGIN
    database_major_version := current_setting('server_version_num')::integer / 10000;

    IF database_major_version <> 17 THEN
        RAISE EXCEPTION 'Workflow Platform requires PostgreSQL 17, found major version %',
            database_major_version;
    END IF;
END
$$;

SET TIME ZONE 'UTC';

