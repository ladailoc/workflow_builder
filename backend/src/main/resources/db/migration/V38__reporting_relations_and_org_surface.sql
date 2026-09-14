-- P2-01 / P2-02: Optional ReportingRelation (spec §8.5 / §25.4), positions.level (§27 schema
-- note) and organization_units.unit_type CHECK constraint.
-- reporting_relations: temporary/matrix/project/functional reporting that does not break the
-- Position tree. It augments (never replaces) the primary organizational hierarchy.

CREATE TABLE reporting_relations (
    id                       UUID PRIMARY KEY,
    subordinate_employee_id  UUID NOT NULL,
    manager_employee_id      UUID NOT NULL,
    relation_type            VARCHAR(64) NOT NULL,
    priority                 INTEGER NOT NULL DEFAULT 0,
    effective_from           DATE NOT NULL,
    effective_to             DATE,
    status                   VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at               TIMESTAMPTZ NOT NULL,
    updated_at               TIMESTAMPTZ NOT NULL,
    lock_version             BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT fk_reporting_relations_subordinate
        FOREIGN KEY (subordinate_employee_id)
        REFERENCES employees (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_reporting_relations_manager
        FOREIGN KEY (manager_employee_id)
        REFERENCES employees (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_reporting_relations_not_self
        CHECK (subordinate_employee_id <> manager_employee_id),
    CONSTRAINT ck_reporting_relations_type
        CHECK (relation_type IN ('MATRIX', 'PROJECT', 'TEMPORARY', 'FUNCTIONAL')),
    CONSTRAINT ck_reporting_relations_status
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'ENDED')),
    CONSTRAINT ck_reporting_relations_effective_dates
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ck_reporting_relations_priority
        CHECK (priority >= 0),
    CONSTRAINT ck_reporting_relations_timestamps
        CHECK (updated_at >= created_at),
    CONSTRAINT ck_reporting_relations_lock
        CHECK (lock_version >= 0)
);

CREATE INDEX ix_reporting_relations_subordinate
    ON reporting_relations (subordinate_employee_id, effective_from, effective_to);
CREATE INDEX ix_reporting_relations_manager
    ON reporting_relations (manager_employee_id, effective_from, effective_to);

-- Primary override: when policy prefers reporting relations, lowest priority value wins among
-- active relations for a subordinate; deterministic tiebreak on manager id.
CREATE UNIQUE INDEX uq_reporting_relations_active_override
    ON reporting_relations (subordinate_employee_id, priority, manager_employee_id)
    WHERE status = 'ACTIVE';

-- positions.level (spec: positions(id, code, name, org_unit_id, reports_to_position_id, status,
-- level)). Backfill level from position_closure depth relative to root: level = max depth of
-- ancestor chain (root = 1).
ALTER TABLE positions
    ADD COLUMN level INTEGER NOT NULL DEFAULT 1;

WITH chain AS (
    SELECT c.descendant_id AS position_id,
           COALESCE(MAX(c.depth), 0) AS depth
    FROM position_closure c
    GROUP BY c.descendant_id
)
UPDATE positions p
SET level = ch.depth + 1
FROM chain ch
WHERE p.id = ch.position_id;

ALTER TABLE positions
    ADD CONSTRAINT ck_positions_level CHECK (level >= 1);

-- Extend the closure maintenance trigger to keep positions.level consistent with the
-- position_closure ancestry (level = ancestry depth + 1) so the spec's level column is always
-- derivable and maintained atomically with the tree.
CREATE OR REPLACE FUNCTION maintain_position_closure()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        -- Self-link at depth 0
        INSERT INTO position_closure (ancestor_id, descendant_id, depth)
        VALUES (NEW.id, NEW.id, 0);

        -- Inherit all ancestors of the parent
        IF NEW.reports_to_position_id IS NOT NULL THEN
            -- Cycle check: ensure NEW.id is not already an ancestor of reports_to
            IF EXISTS (
                SELECT 1 FROM position_closure
                WHERE ancestor_id = NEW.id
                  AND descendant_id = NEW.reports_to_position_id
            ) THEN
                RAISE EXCEPTION 'Position hierarchy cycle detected: % would become ancestor of %',
                    NEW.id, NEW.reports_to_position_id
                USING ERRCODE = '23514';
            END IF;

            INSERT INTO position_closure (ancestor_id, descendant_id, depth)
            SELECT ancestor_id, NEW.id, depth + 1
            FROM position_closure
            WHERE descendant_id = NEW.reports_to_position_id;
        END IF;

        -- Maintain level from closure depth (AFTER trigger: update the row directly; JPA's
        -- insert value is corrected here after closure rows exist).
        UPDATE positions p
        SET level = GREATEST(1, (SELECT COALESCE(MAX(c.depth), 0) + 1 FROM position_closure c
                                 WHERE c.descendant_id = p.id))
        WHERE p.id = NEW.id;

    ELSIF TG_OP = 'UPDATE' AND (
        NEW.reports_to_position_id IS DISTINCT FROM OLD.reports_to_position_id
    ) THEN
        -- Remove old ancestry edges for this node and all its descendants
        DELETE FROM position_closure
        WHERE descendant_id IN (
            SELECT descendant_id FROM position_closure WHERE ancestor_id = OLD.id
        )
        AND ancestor_id NOT IN (
            SELECT descendant_id FROM position_closure WHERE ancestor_id = OLD.id
        );

        -- Re-insert self-links and ancestor links for OLD.id subtree
        -- Re-insert self
        INSERT INTO position_closure (ancestor_id, descendant_id, depth)
        SELECT d.ancestor_id, NEW.id, 0
        FROM (SELECT NEW.id AS ancestor_id) d
        ON CONFLICT DO NOTHING;

        -- Inherit from new parent
        IF NEW.reports_to_position_id IS NOT NULL THEN
            IF EXISTS (
                SELECT 1 FROM position_closure
                WHERE ancestor_id = NEW.id
                  AND descendant_id = NEW.reports_to_position_id
            ) THEN
                RAISE EXCEPTION 'Position hierarchy cycle detected on reparent: % -> %',
                    NEW.id, NEW.reports_to_position_id
                USING ERRCODE = '23514';
            END IF;

            -- Rebuild: for every descendant of NEW.id and every ancestor of NEW.reports_to_position_id
            INSERT INTO position_closure (ancestor_id, descendant_id, depth)
            SELECT anc.ancestor_id, desc_node.descendant_id, anc.depth + desc_node.depth + 1
            FROM position_closure anc
            CROSS JOIN position_closure desc_node
            WHERE anc.descendant_id = NEW.reports_to_position_id
              AND desc_node.ancestor_id = NEW.id
            ON CONFLICT (ancestor_id, descendant_id) DO UPDATE
                SET depth = EXCLUDED.depth;
        END IF;

        -- Maintain level for NEW.id and its descendants from rebuilt closure
        UPDATE positions p
        SET level = GREATEST(1, (SELECT COALESCE(MAX(c.depth), 0) + 1 FROM position_closure c
                                 WHERE c.descendant_id = p.id))
        WHERE p.id = NEW.id
           OR p.id IN (
              SELECT descendant_id FROM position_closure WHERE ancestor_id = NEW.id
           );

    ELSIF TG_OP = 'DELETE' THEN
        -- Cascade deletes handle closure via FK
        NULL;
    END IF;

    RETURN NEW;
END
$$;

-- organization_units.unit_type CHECK (existing rows keep their values; allowed vocabulary per
-- canonical org model).
ALTER TABLE organization_units
    ADD CONSTRAINT ck_org_units_unit_type
        CHECK (unit_type IN ('COMPANY', 'DIVISION', 'DEPARTMENT', 'TEAM', 'BRANCH', 'UNIT'));
