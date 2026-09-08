# Workflow graph persistence

`workflow_nodes` and `workflow_edges` are normalized design-time rows owned by a
`WorkflowVersion`. They support builder editing, validation, semantic diff, and compilation; they
are not runtime routing state.

## Invariants

- `node_key` is immutable application identity inside one version and is unique per version.
- Edge destination exists only in `workflow_edges`; routing destination fields are rejected from
  node `config_json`.
- Composite foreign keys guarantee that an edge source and target belong to its version.
- Node and edge writes are allowed only while the owning version is `DRAFT`. Both the service and
  database triggers enforce this rule.
- Every graph mutation checks the WorkflowVersion optimistic-lock value and draft revision, bumps
  both values, and clears any stale checksum/execution package.
- Deleting a mutable draft cascades to its normalized graph. Published versions remain protected by
  the existing version immutability trigger.

Runtime routing, graph validation, compilation, and publish orchestration are intentionally outside
this persistence slice.
