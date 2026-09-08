CREATE OR REPLACE FUNCTION canonical_type_descriptor_is_valid(descriptor JSONB)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    type_name TEXT;
BEGIN
    IF descriptor IS NULL OR jsonb_typeof(descriptor) <> 'object' THEN
        RETURN FALSE;
    END IF;
    IF descriptor - ARRAY['type', 'nullable', 'itemType'] <> '{}'::JSONB THEN
        RETURN FALSE;
    END IF;
    IF NOT descriptor ? 'type'
        OR jsonb_typeof(descriptor -> 'type') <> 'string'
        OR NOT descriptor ? 'nullable'
        OR jsonb_typeof(descriptor -> 'nullable') <> 'boolean' THEN
        RETURN FALSE;
    END IF;

    type_name := descriptor ->> 'type';
    IF type_name NOT IN (
        'STRING', 'NUMBER', 'INTEGER', 'BOOLEAN', 'DATE', 'DATETIME',
        'DURATION', 'MONEY', 'USER_ID', 'USER', 'DEPARTMENT_ID', 'GROUP_ID',
        'ENUM', 'OBJECT', 'ARRAY', 'FILE_REF', 'FILE_LIST'
    ) THEN
        RETURN FALSE;
    END IF;

    IF type_name = 'ARRAY' THEN
        RETURN descriptor ? 'itemType'
            AND canonical_type_descriptor_is_valid(descriptor -> 'itemType');
    END IF;
    IF type_name = 'FILE_LIST' THEN
        RETURN descriptor -> 'itemType'
            = '{"type":"FILE_REF","nullable":false}'::JSONB;
    END IF;
    RETURN NOT descriptor ? 'itemType';
END
$$;

ALTER TABLE workflow_variables
    ADD COLUMN type_descriptor_json JSONB;

UPDATE workflow_variables
SET type_descriptor_json = CASE
    WHEN type = 'USER_LIST' THEN
        '{"type":"ARRAY","nullable":false,"itemType":{"type":"USER","nullable":false}}'::JSONB
    WHEN type = 'ARRAY' THEN
        '{"type":"ARRAY","nullable":false,"itemType":{"type":"OBJECT","nullable":true}}'::JSONB
    WHEN type = 'FILE_LIST' THEN
        '{"type":"FILE_LIST","nullable":false,"itemType":{"type":"FILE_REF","nullable":false}}'::JSONB
    ELSE jsonb_build_object('type', type, 'nullable', false)
END;

ALTER TABLE workflow_variables
    ALTER COLUMN type_descriptor_json SET NOT NULL,
    DROP CONSTRAINT ck_workflow_variables_type,
    DROP COLUMN type,
    ADD CONSTRAINT ck_workflow_variables_type_descriptor
        CHECK (canonical_type_descriptor_is_valid(type_descriptor_json));

