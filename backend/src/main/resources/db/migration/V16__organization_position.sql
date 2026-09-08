-- V16: Organization Unit, Position, Employee, Assignment
-- No manager userId in OrgUnit — only managerPositionId (FK to positions).
-- Position has orgUnitId + reportsToPositionId (hierarchy via closure).
-- PositionAssignment: employee → position with effective dating, primary flag, status.
-- Closure tables maintained transactionally (triggers).
-- Cycle guard: no self-report, no ancestor loop.

-- ─────────────────────────────────────────────────────────────────
-- 1. EMPLOYEES
-- ─────────────────────────────────────────────────────────────────
CREATE TABLE employees (
    id               UUID PRIMARY KEY,
    user_id          UUID NOT NULL UNIQUE,
    employee_code    VARCHAR(64) NOT NULL UNIQUE,
    full_name        VARCHAR(256) NOT NULL,
    email            VARCHAR(256) NOT NULL,
    status           VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,
    lock_version     BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT ck_employees_status
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'TERMINATED')),
    CONSTRAINT ck_employees_timestamps
        CHECK (updated_at >= created_at),
    CONSTRAINT ck_employees_lock
        CHECK (lock_version >= 0)
);

CREATE INDEX ix_employees_user_id ON employees (user_id);
CREATE INDEX ix_employees_status  ON employees (status);

-- ─────────────────────────────────────────────────────────────────
-- 2. ORGANIZATION UNITS
-- ─────────────────────────────────────────────────────────────────
CREATE TABLE organization_units (
    id                   UUID PRIMARY KEY,
    unit_code            VARCHAR(64) NOT NULL UNIQUE,
    name                 VARCHAR(256) NOT NULL,
    description          TEXT,
    parent_unit_id       UUID,
    manager_position_id  UUID,        -- FK added after positions table
    unit_type            VARCHAR(64) NOT NULL DEFAULT 'DEPARTMENT',
    status               VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at           TIMESTAMPTZ NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL,
    lock_version         BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT fk_org_units_parent
        FOREIGN KEY (parent_unit_id)
        REFERENCES organization_units (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_org_units_no_self_parent
        CHECK (parent_unit_id IS DISTINCT FROM id),
    CONSTRAINT ck_org_units_status
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'DISSOLVED')),
    CONSTRAINT ck_org_units_timestamps
        CHECK (updated_at >= created_at),
    CONSTRAINT ck_org_units_lock
        CHECK (lock_version >= 0)
);

CREATE INDEX ix_org_units_parent ON organization_units (parent_unit_id);
CREATE INDEX ix_org_units_status ON organization_units (status);

-- ─────────────────────────────────────────────────────────────────
-- 3. ORGANIZATION UNIT CLOSURE
-- ─────────────────────────────────────────────────────────────────
CREATE TABLE organization_unit_closure (
    ancestor_id   UUID NOT NULL,
    descendant_id UUID NOT NULL,
    depth         INT NOT NULL,

    PRIMARY KEY (ancestor_id, descendant_id),
    CONSTRAINT fk_ou_closure_ancestor
        FOREIGN KEY (ancestor_id)
        REFERENCES organization_units (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_ou_closure_descendant
        FOREIGN KEY (descendant_id)
        REFERENCES organization_units (id)
        ON DELETE CASCADE,
    CONSTRAINT ck_ou_closure_depth
        CHECK (depth >= 0)
);

CREATE INDEX ix_ou_closure_ancestor_depth   ON organization_unit_closure (ancestor_id, depth);
CREATE INDEX ix_ou_closure_descendant_depth ON organization_unit_closure (descendant_id, depth);

-- ─────────────────────────────────────────────────────────────────
-- 4. POSITIONS
-- ─────────────────────────────────────────────────────────────────
CREATE TABLE positions (
    id                    UUID PRIMARY KEY,
    position_code         VARCHAR(64) NOT NULL UNIQUE,
    title                 VARCHAR(256) NOT NULL,
    org_unit_id           UUID NOT NULL,
    reports_to_position_id UUID,
    is_head_of_unit       BOOLEAN NOT NULL DEFAULT FALSE,
    status                VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at            TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL,
    lock_version          BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT fk_positions_org_unit
        FOREIGN KEY (org_unit_id)
        REFERENCES organization_units (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_positions_reports_to
        FOREIGN KEY (reports_to_position_id)
        REFERENCES positions (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_positions_no_self_report
        CHECK (reports_to_position_id IS DISTINCT FROM id),
    CONSTRAINT ck_positions_status
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'ABOLISHED')),
    CONSTRAINT ck_positions_timestamps
        CHECK (updated_at >= created_at),
    CONSTRAINT ck_positions_lock
        CHECK (lock_version >= 0)
);

-- At most one active head per unit
CREATE UNIQUE INDEX uq_positions_head_of_unit
    ON positions (org_unit_id)
    WHERE is_head_of_unit = TRUE AND status = 'ACTIVE';

CREATE INDEX ix_positions_org_unit       ON positions (org_unit_id);
CREATE INDEX ix_positions_reports_to     ON positions (reports_to_position_id);
CREATE INDEX ix_positions_status         ON positions (status);

-- Now add manager_position FK to organization_units
ALTER TABLE organization_units
    ADD CONSTRAINT fk_org_units_manager_position
    FOREIGN KEY (manager_position_id)
    REFERENCES positions (id)
    ON DELETE SET NULL;

-- ─────────────────────────────────────────────────────────────────
-- 5. POSITION CLOSURE
-- ─────────────────────────────────────────────────────────────────
CREATE TABLE position_closure (
    ancestor_id   UUID NOT NULL,
    descendant_id UUID NOT NULL,
    depth         INT NOT NULL,

    PRIMARY KEY (ancestor_id, descendant_id),
    CONSTRAINT fk_pos_closure_ancestor
        FOREIGN KEY (ancestor_id)
        REFERENCES positions (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_pos_closure_descendant
        FOREIGN KEY (descendant_id)
        REFERENCES positions (id)
        ON DELETE CASCADE,
    CONSTRAINT ck_pos_closure_depth
        CHECK (depth >= 0)
);

CREATE INDEX ix_pos_closure_ancestor_depth   ON position_closure (ancestor_id, depth);
CREATE INDEX ix_pos_closure_descendant_depth ON position_closure (descendant_id, depth);

-- ─────────────────────────────────────────────────────────────────
-- 6. POSITION ASSIGNMENTS
-- ─────────────────────────────────────────────────────────────────
CREATE TABLE position_assignments (
    id              UUID PRIMARY KEY,
    employee_id     UUID NOT NULL,
    position_id     UUID NOT NULL,
    is_primary      BOOLEAN NOT NULL DEFAULT FALSE,
    assignment_type VARCHAR(64) NOT NULL DEFAULT 'PERMANENT',
    effective_from  DATE NOT NULL,
    effective_to    DATE,
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    lock_version    BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT fk_pos_assignments_employee
        FOREIGN KEY (employee_id)
        REFERENCES employees (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_pos_assignments_position
        FOREIGN KEY (position_id)
        REFERENCES positions (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_pos_assignments_status
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'ENDED')),
    CONSTRAINT ck_pos_assignments_effective_dates
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ck_pos_assignments_type
        CHECK (assignment_type IN ('PERMANENT', 'ACTING', 'SECONDMENT', 'PROBATION')),
    CONSTRAINT ck_pos_assignments_timestamps
        CHECK (updated_at >= created_at),
    CONSTRAINT ck_pos_assignments_lock
        CHECK (lock_version >= 0)
);

-- Only one active primary assignment per employee
CREATE UNIQUE INDEX uq_pos_assignments_primary
    ON position_assignments (employee_id)
    WHERE is_primary = TRUE AND status = 'ACTIVE';

CREATE INDEX ix_pos_assignments_employee   ON position_assignments (employee_id, status);
CREATE INDEX ix_pos_assignments_position   ON position_assignments (position_id, status);
CREATE INDEX ix_pos_assignments_effective  ON position_assignments (effective_from, effective_to);

-- ─────────────────────────────────────────────────────────────────
-- 7. POSITION CLOSURE MAINTENANCE TRIGGERS
--    Triggered on INSERT/UPDATE/DELETE of positions.
--    On INSERT: build closure entries from the new node and all ancestors.
--    On UPDATE (reports_to changes): rebuild affected subtree.
--    Cycle guard: raise if inserting/updating would create a loop.
-- ─────────────────────────────────────────────────────────────────

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

    ELSIF TG_OP = 'DELETE' THEN
        -- Cascade deletes handle closure via FK
        NULL;
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER trg_positions_maintain_closure
AFTER INSERT OR UPDATE ON positions
FOR EACH ROW EXECUTE FUNCTION maintain_position_closure();

-- ─────────────────────────────────────────────────────────────────
-- 8. ORGANIZATION UNIT CLOSURE MAINTENANCE TRIGGERS
-- ─────────────────────────────────────────────────────────────────

CREATE OR REPLACE FUNCTION maintain_org_unit_closure()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO organization_unit_closure (ancestor_id, descendant_id, depth)
        VALUES (NEW.id, NEW.id, 0);

        IF NEW.parent_unit_id IS NOT NULL THEN
            IF EXISTS (
                SELECT 1 FROM organization_unit_closure
                WHERE ancestor_id = NEW.id
                  AND descendant_id = NEW.parent_unit_id
            ) THEN
                RAISE EXCEPTION 'OrgUnit hierarchy cycle detected'
                    USING ERRCODE = '23514';
            END IF;

            INSERT INTO organization_unit_closure (ancestor_id, descendant_id, depth)
            SELECT ancestor_id, NEW.id, depth + 1
            FROM organization_unit_closure
            WHERE descendant_id = NEW.parent_unit_id;
        END IF;

    ELSIF TG_OP = 'UPDATE' AND (
        NEW.parent_unit_id IS DISTINCT FROM OLD.parent_unit_id
    ) THEN
        DELETE FROM organization_unit_closure
        WHERE descendant_id IN (
            SELECT descendant_id FROM organization_unit_closure WHERE ancestor_id = OLD.id
        )
        AND ancestor_id NOT IN (
            SELECT descendant_id FROM organization_unit_closure WHERE ancestor_id = OLD.id
        );

        INSERT INTO organization_unit_closure (ancestor_id, descendant_id, depth)
        VALUES (NEW.id, NEW.id, 0)
        ON CONFLICT DO NOTHING;

        IF NEW.parent_unit_id IS NOT NULL THEN
            IF EXISTS (
                SELECT 1 FROM organization_unit_closure
                WHERE ancestor_id = NEW.id
                  AND descendant_id = NEW.parent_unit_id
            ) THEN
                RAISE EXCEPTION 'OrgUnit hierarchy cycle detected on reparent'
                    USING ERRCODE = '23514';
            END IF;

            INSERT INTO organization_unit_closure (ancestor_id, descendant_id, depth)
            SELECT anc.ancestor_id, desc_node.descendant_id, anc.depth + desc_node.depth + 1
            FROM organization_unit_closure anc
            CROSS JOIN organization_unit_closure desc_node
            WHERE anc.descendant_id = NEW.parent_unit_id
              AND desc_node.ancestor_id = NEW.id
            ON CONFLICT (ancestor_id, descendant_id) DO UPDATE
                SET depth = EXCLUDED.depth;
        END IF;
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER trg_org_units_maintain_closure
AFTER INSERT OR UPDATE ON organization_units
FOR EACH ROW EXECUTE FUNCTION maintain_org_unit_closure();
