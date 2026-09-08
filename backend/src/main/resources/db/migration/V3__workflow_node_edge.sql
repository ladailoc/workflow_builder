CREATE OR REPLACE FUNCTION workflow_node_config_has_routing_destination(value JSONB)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    child JSONB;
BEGIN
    IF value IS NULL THEN
        RETURN FALSE;
    END IF;

    IF jsonb_typeof(value) = 'object' THEN
        IF value ?| ARRAY[
            'targetNodeId',
            'target_node_id',
            'destinationNodeId',
            'destination_node_id',
            'nextNodeId',
            'next_node_id'
        ] THEN
            RETURN TRUE;
        END IF;

        FOR child IN SELECT entry.value FROM jsonb_each(value) AS entry
        LOOP
            IF workflow_node_config_has_routing_destination(child) THEN
                RETURN TRUE;
            END IF;
        END LOOP;
    ELSIF jsonb_typeof(value) = 'array' THEN
        FOR child IN SELECT element.value FROM jsonb_array_elements(value) AS element
        LOOP
            IF workflow_node_config_has_routing_destination(child) THEN
                RETURN TRUE;
            END IF;
        END LOOP;
    END IF;

    RETURN FALSE;
END
$$;

CREATE TABLE workflow_nodes (
    id UUID PRIMARY KEY,
    workflow_version_id UUID NOT NULL,
    node_key VARCHAR(128) NOT NULL,
    node_type VARCHAR(128) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    config_schema_version INTEGER NOT NULL,
    config_json JSONB NOT NULL DEFAULT '{}'::JSONB,
    input_schema_json JSONB,
    output_schema_json JSONB,
    position_json JSONB NOT NULL DEFAULT '{}'::JSONB,

    CONSTRAINT fk_workflow_nodes_version
        FOREIGN KEY (workflow_version_id)
        REFERENCES workflow_versions (id)
        ON DELETE CASCADE,
    CONSTRAINT uq_workflow_nodes_version_key
        UNIQUE (workflow_version_id, node_key),
    CONSTRAINT uq_workflow_nodes_id_version
        UNIQUE (id, workflow_version_id),
    CONSTRAINT ck_workflow_nodes_config_schema_version
        CHECK (config_schema_version > 0),
    CONSTRAINT ck_workflow_nodes_config_object
        CHECK (jsonb_typeof(config_json) = 'object'),
    CONSTRAINT ck_workflow_nodes_input_schema_object
        CHECK (input_schema_json IS NULL OR jsonb_typeof(input_schema_json) = 'object'),
    CONSTRAINT ck_workflow_nodes_output_schema_object
        CHECK (output_schema_json IS NULL OR jsonb_typeof(output_schema_json) = 'object'),
    CONSTRAINT ck_workflow_nodes_position_object
        CHECK (jsonb_typeof(position_json) = 'object'),
    CONSTRAINT ck_workflow_nodes_no_routing_destination
        CHECK (NOT workflow_node_config_has_routing_destination(config_json))
);

CREATE TABLE workflow_edges (
    id UUID PRIMARY KEY,
    workflow_version_id UUID NOT NULL,
    source_node_id UUID NOT NULL,
    source_port VARCHAR(128) NOT NULL,
    target_node_id UUID NOT NULL,
    condition_json JSONB,
    priority INTEGER NOT NULL DEFAULT 0,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    transition_type VARCHAR(32) NOT NULL,
    label VARCHAR(255),
    config_json JSONB NOT NULL DEFAULT '{}'::JSONB,

    CONSTRAINT fk_workflow_edges_version
        FOREIGN KEY (workflow_version_id)
        REFERENCES workflow_versions (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_workflow_edges_source_same_version
        FOREIGN KEY (source_node_id, workflow_version_id)
        REFERENCES workflow_nodes (id, workflow_version_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_workflow_edges_target_same_version
        FOREIGN KEY (target_node_id, workflow_version_id)
        REFERENCES workflow_nodes (id, workflow_version_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_workflow_edges_condition_object
        CHECK (condition_json IS NULL OR jsonb_typeof(condition_json) = 'object'),
    CONSTRAINT ck_workflow_edges_priority
        CHECK (priority >= 0),
    CONSTRAINT ck_workflow_edges_transition_type
        CHECK (transition_type IN ('NORMAL', 'CONDITIONAL', 'REWORK', 'RETURN')),
    CONSTRAINT ck_workflow_edges_config_object
        CHECK (jsonb_typeof(config_json) = 'object')
);

CREATE INDEX ix_workflow_nodes_version_type
    ON workflow_nodes (workflow_version_id, node_type);

CREATE INDEX ix_workflow_edges_routing
    ON workflow_edges (workflow_version_id, source_node_id, source_port, priority);

CREATE INDEX ix_workflow_edges_target
    ON workflow_edges (workflow_version_id, target_node_id);

CREATE OR REPLACE FUNCTION guard_draft_workflow_graph_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    affected_version_id UUID;
    affected_status VARCHAR(32);
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.workflow_version_id <> OLD.workflow_version_id THEN
        RAISE EXCEPTION 'Graph rows cannot move between WorkflowVersions'
            USING ERRCODE = '23514';
    END IF;

    affected_version_id := CASE
        WHEN TG_OP = 'DELETE' THEN OLD.workflow_version_id
        ELSE NEW.workflow_version_id
    END;

    SELECT status
      INTO affected_status
      FROM workflow_versions
     WHERE id = affected_version_id;

    -- The parent is no longer visible while an ON DELETE CASCADE removes a mutable draft graph.
    IF affected_status IS NULL AND TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;

    IF affected_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'Only DRAFT WorkflowVersion graph is mutable'
            USING ERRCODE = '23514';
    END IF;

    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END
$$;

CREATE TRIGGER trg_workflow_nodes_draft_only
BEFORE INSERT OR UPDATE OR DELETE ON workflow_nodes
FOR EACH ROW EXECUTE FUNCTION guard_draft_workflow_graph_mutation();

CREATE TRIGGER trg_workflow_edges_draft_only
BEFORE INSERT OR UPDATE OR DELETE ON workflow_edges
FOR EACH ROW EXECUTE FUNCTION guard_draft_workflow_graph_mutation();
