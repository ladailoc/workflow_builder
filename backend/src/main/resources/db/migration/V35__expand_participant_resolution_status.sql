-- V35__expand_participant_resolution_status.sql
-- Expand participant resolution status check constraint to support normative values:
-- RESOLVED, VACANT, INACTIVE_ASSIGNEE, NOT_FOUND, AMBIGUOUS per workflow_spec.md §9.4.

ALTER TABLE participant_snapshots DROP CONSTRAINT IF EXISTS ck_participant_snapshots_resolution_status;
ALTER TABLE participant_snapshots ADD CONSTRAINT ck_participant_snapshots_resolution_status
    CHECK (resolution_status IN ('RESOLVED', 'VACANT', 'INACTIVE_ASSIGNEE', 'NOT_FOUND', 'AMBIGUOUS', 'NO_MATCH', 'FAILED'));

ALTER TABLE participant_snapshots DROP CONSTRAINT IF EXISTS ck_participant_snapshots_resolved_user;
ALTER TABLE participant_snapshots ADD CONSTRAINT ck_participant_snapshots_resolved_user
    CHECK ((resolution_status = 'RESOLVED' AND resolved_user_id IS NOT NULL)
        OR (resolution_status <> 'RESOLVED'));
