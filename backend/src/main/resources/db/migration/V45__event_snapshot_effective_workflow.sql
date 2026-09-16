-- Preserve the effective workflow selected at Category ticket creation. A tenant override can
-- legitimately differ from the CategoryVersion default workflow, while revisions must remain
-- bound to the exact workflow used by the Event.
ALTER TABLE event_workflow_input_snapshots
    ADD COLUMN workflow_version_id UUID REFERENCES workflow_versions(id) ON DELETE RESTRICT;

UPDATE event_workflow_input_snapshots s
SET workflow_version_id = e.workflow_version_id
FROM events e
WHERE e.id = s.event_id
  AND s.workflow_version_id IS NULL;

CREATE INDEX ix_event_input_snapshot_workflow
    ON event_workflow_input_snapshots(workflow_version_id);
