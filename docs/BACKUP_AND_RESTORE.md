# Workflow Platform — Backup & Disaster Recovery Runbook

This document defines the production backup and disaster recovery strategy for the **Workflow Platform**, with specific focus on immutable audit logs, workflow history, durable jobs, task state machines, and attachment recovery.

---

## 1. Objectives & RPO / RTO SLAs

| Objective | Target | Rationale |
|---|---|---|
| **RPO (Recovery Point Objective)** | `< 15 minutes` | WAL archiving (Point-In-Time Recovery) allows restoring transactions up to the latest completed WAL segment. Logical backups capture daily baselines. |
| **RTO (Recovery Time Objective)** | `< 30 minutes` | Automated restore scripts (`restore.sh` / `restore.ps1`) drop, recreate, restore custom dumps, verify Flyway migrations, and run integrity checks in under 5 minutes. |
| **Integrity Guarantee** | `Zero Historical Tampering` | Immutable triggers on `workflow_versions`, `events`, `node_executions`, `task_executions`, and `audit_events` are restored with full constraint enforcement. |

---

## 2. Backup Strategy & Architecture

### 2.1 Logical Backup (`pg_dump`)
- **Format**: PostgreSQL Custom format (`-Fc`), compressed with zlib.
- **Blob Handling**: `-b` flag ensures large objects/blobs are included.
- **Frequency**:
  - Daily full backup at 02:00 UTC.
  - Pre-deployment / pre-migration backup before applying any new Flyway migration.
- **Checksum**: Every backup produces a SHA-256 digest (`.dump.sha256`) to guarantee tamper-evident storage.
- **Script Location**: `infra/scripts/backup.sh` (Linux/Container) and `infra/scripts/backup.ps1` (Windows).

### 2.2 Physical Backup & Continuous WAL Archiving (PITR)
- In production cloud/managed environments (AWS RDS, GCP Cloud SQL, or on-prem Patroni):
  - Continuous WAL streaming to encrypted cloud object storage (e.g. S3 / GCS via `pgBackRest` or `wal-g`).
  - Enables Point-In-Time Recovery to any specific second between daily baselines.

### 2.3 Retention Policy
| Tier | Frequency | Retention Window | Destination |
|---|---|---|---|
| **Daily Baselines** | Every 24 hours | 7 days | Local SSD + Hot Object Storage (`s3://wf-backups/daily/`) |
| **Weekly Snapshots** | Sunday 02:00 UTC | 4 weeks | Cold Object Storage (`s3://wf-backups/weekly/`) |
| **Monthly Archives** | 1st of month | 12 months / 7 years for compliance | Immutable WORM Glacier Vault |

Automated pruning is performed by `backup.sh` using the `RETENTION_DAYS` parameter.

---

## 3. Flyway Compatibility & Schema Migrations

Workflow Platform uses Flyway for schema management (currently 34 versioned migrations).

### Compatibility Rules:
1. **`flyway_schema_history` is Authoritative**:
   The backup dump contains the `flyway_schema_history` table with exact checksums of all applied scripts (`V1` to `V34`).
2. **Post-Restore Verification**:
   After `pg_restore`, the platform runs `flyway.validate()` on startup.
   - If the code version matches the restored dump version, validation succeeds seamlessly.
   - If restoring an older dump into a newer application deployment, Flyway automatically runs the remaining pending forward migrations.
   - **Never delete or truncate `flyway_schema_history`**.
3. **No Drift Pre-requisite**:
   Post-restore healthchecks query `SELECT count(*) FROM flyway_schema_history WHERE success = true`. Any failed migration (`success = false`) aborts recovery.

---

## 4. Object Storage Attachment Recovery Considerations

File attachments are split between the relational database and object storage:
- **Database (`file_attachments`, `file_links`)**: Holds authoritative metadata, file size, MIME type, antivirus scan status, owner entities, and SHA-256 digests (`sha256_checksum`).
- **Object Storage (S3 / MinIO / Local FS)**: Holds raw binary payload at `storage_path`.

### Recovery Scenarios & Mitigations:
1. **Coordinated Point-In-Time Restoration**:
   - S3 Versioning / Object Locking must be enabled on the attachments bucket.
   - When restoring DB to timestamp `T`, object storage must be synced or rolled back to snapshot at timestamp `T`.
2. **Missing Binary Detection (Dangling DB reference)**:
   - Run the attachment audit query:
     ```sql
     SELECT id, original_name, storage_path, sha256_checksum FROM file_attachments WHERE scan_status = 'CLEAN';
     ```
   - Verify every `storage_path` exists on the target storage.
   - If a binary is missing, mark `scan_status = 'REJECTED'` with details `{"error": "BLOB_LOST_IN_DISASTER"}` to prevent serving corrupted downloads.
3. **Orphaned Storage Blobs**:
   - Blobs existing in bucket but missing in `file_attachments` are quarantined into an `/orphans/` prefix for 30 days before deletion.

---

## 5. Step-by-Step Restoration Procedure (Runbook)

### Phase 1: Preparation & Isolation
1. Stop application traffic to prevent write splits:
   ```bash
   # Scale down backend services
   docker compose -f docker-compose.staging.yml stop backend
   ```
2. Retrieve the target backup dump and checksum file from storage:
   ```bash
   aws s3 cp s3://wf-backups/daily/workflow_platform_20260909_020000Z.dump /var/backups/
   aws s3 cp s3://wf-backups/daily/workflow_platform_20260909_020000Z.dump.sha256 /var/backups/
   ```

### Phase 2: Checksum Verification
```bash
cd /var/backups
sha256sum -c workflow_platform_20260909_020000Z.dump.sha256
# Expected output: OK
```

### Phase 3: Execute Restoration
Execute `restore.sh` specifying the dump file and target database:
```bash
export PGHOST=postgres.internal
export PGPORT=5432
export PGUSER=workflow
export PGPASSWORD="<db-secret>"

/opt/workflow-platform/infra/scripts/restore.sh \
  /var/backups/workflow_platform_20260909_020000Z.dump \
  workflow_platform
```

### Phase 4: Post-Restore Verification Checklist
Run verification queries against the restored database:

```sql
-- 1. Flyway status
SELECT version, description, success, installed_on FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;

-- 2. Workflow Versions & Drafts
SELECT status, count(*), max(version_no) FROM workflow_versions GROUP BY status;

-- 3. Tickets & Events
SELECT status, count(*) FROM tickets GROUP BY status;
SELECT status, count(*) FROM events GROUP BY status;

-- 4. Tasks & Incomplete Items
SELECT status, count(*) FROM task_executions GROUP BY status;

-- 5. Durable Jobs Outbox
SELECT status, count(*) FROM workflow_jobs GROUP BY status;

-- 6. Integration Executions
SELECT status, count(*) FROM integration_executions GROUP BY status;

-- 7. Audit Events
SELECT count(*) AS total_audit_entries, max(occurred_at) AS latest_audit FROM audit_events;
```

### Phase 5: Bring Application Online & Smoke Test
1. Start backend service:
   ```bash
   docker compose -f docker-compose.staging.yml up -d backend
   ```
2. Verify Spring Boot Actuator Health:
   ```bash
   curl -fsS http://localhost:8080/actuator/health | jq .
   # Verify status == "UP" and components.db.status == "UP"
   ```
3. Run Staging Smoke Test Suite:
   ```bash
   mvn test -Dtest=StagingDeploymentSmokeIT
   ```
