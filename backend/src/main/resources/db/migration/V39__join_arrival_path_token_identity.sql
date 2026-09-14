-- P2-12 (§25.14): join arrival identity must follow the logical path token, not only the
-- inbound NodeExecution id. Rework/retry of the same logical branch (new NodeExecution, same
-- pathToken) must not satisfy the join twice; a genuinely new path token (new iteration/cycle)
-- is the only thing that counts as a new arrival.

ALTER TABLE join_arrived_branches
    ADD COLUMN inbound_path_token VARCHAR(256);

-- Backfill from the inbound node executions' current path tokens. Rows whose execution no
-- longer exists keep NULL (legacy arrivals: dedup falls back to inbound_execution_id).
UPDATE join_arrived_branches b
SET inbound_path_token = ne.path_token
FROM node_executions ne
WHERE ne.id = b.inbound_execution_id
  AND b.inbound_path_token IS NULL;

-- Arrival identity: one arrival per (join_state, pathToken). NULL pathToken (legacy rows)
-- keeps the old execution-id based uniqueness via the existing constraint.
CREATE UNIQUE INDEX uq_join_arrivals_state_path_token
    ON join_arrived_branches (join_state_id, inbound_path_token)
    WHERE inbound_path_token IS NOT NULL;

-- Cycle identity: same logical branch arriving again in a NEW cycle/iteration is a new arrival.
-- The stored path_token distinguishes rework cycles (RoutingService.childPath embeds the edge
-- id per activation, and rework edges produce a fresh cycleId so the token differs).

ALTER TABLE join_arrived_branches
    ADD COLUMN inbound_cycle_id UUID;

UPDATE join_arrived_branches b
SET inbound_cycle_id = ne.cycle_id
FROM node_executions ne
WHERE ne.id = b.inbound_execution_id
  AND b.inbound_cycle_id IS NULL;
