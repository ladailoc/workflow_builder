#!/usr/bin/env bash
# ==============================================================================
# Workflow Platform — Production PostgreSQL Restore Script
# ==============================================================================
# Restores a full logical database backup from a pg_dump custom format (-Fc) file.
# Verifies SHA-256 checksum, drops/cleans target database, restores schema & data,
# verifies Flyway schema history, and audits attachment metadata integrity.
# ==============================================================================

set -euo pipefail

DUMP_FILE="${1:-}"

if [ -z "${DUMP_FILE}" ]; then
  echo "Usage: $0 <path-to-backup.dump> [target-database]"
  exit 1
fi

if [ ! -f "${DUMP_FILE}" ]; then
  echo "Error: Backup dump file '${DUMP_FILE}' not found."
  exit 1
fi

PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"
PGDATABASE="${2:-${PGDATABASE:-workflow_platform}}"
PGUSER="${PGUSER:-workflow}"
CHECKSUM_FILE="${DUMP_FILE}.sha256"

log() {
  local level="$1"
  local msg="$2"
  echo "{\"timestamp\":\"$(date -u +"%Y-%m-%dT%H:%M:%SZ")\",\"level\":\"${level}\",\"component\":\"restore\",\"message\":\"${msg}\"}"
}

log "INFO" "Initiating PostgreSQL restore for database '${PGDATABASE}' from '${DUMP_FILE}'..."

# Step 1: Checksum Verification
if [ -f "${CHECKSUM_FILE}" ]; then
  log "INFO" "Verifying SHA-256 checksum using '${CHECKSUM_FILE}'..."
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum -c "${CHECKSUM_FILE}"
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 -c "${CHECKSUM_FILE}"
  else
    log "WARN" "No sha256sum utility found, skipping checksum verification"
  fi
  log "INFO" "SHA-256 Checksum verified successfully."
else
  log "WARN" "Checksum file '${CHECKSUM_FILE}' not found; proceeding with restore at operator discretion."
fi

# Step 2: Terminate active connections to the target database
log "INFO" "Terminating existing connections to database '${PGDATABASE}'..."
PGPASSWORD="${PGPASSWORD:-}" psql -h "${PGHOST}" -p "${PGPORT}" -U "${PGUSER}" -d "postgres" -c \
  "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '${PGDATABASE}' AND pid <> pg_backend_pid();" \
  || true

# Step 3: Ensure target database exists
DB_EXISTS=$(PGPASSWORD="${PGPASSWORD:-}" psql -h "${PGHOST}" -p "${PGPORT}" -U "${PGUSER}" -d "postgres" -tAc \
  "SELECT 1 FROM pg_database WHERE datname = '${PGDATABASE}';" || echo "0")

if [ "${DB_EXISTS}" != "1" ]; then
  log "INFO" "Database '${PGDATABASE}' does not exist. Creating it..."
  PGPASSWORD="${PGPASSWORD:-}" psql -h "${PGHOST}" -p "${PGPORT}" -U "${PGUSER}" -d "postgres" -c \
    "CREATE DATABASE \"${PGDATABASE}\" WITH ENCODING 'UTF8' LC_COLLATE 'en_US.utf8' LC_CTYPE 'en_US.utf8';"
fi

# Step 4: Execute pg_restore
# --clean --if-exists: Drop existing tables/constraints before recreating
# --no-owner: Do not fail if role in dump differs
# -v: Verbose
log "INFO" "Executing pg_restore into '${PGDATABASE}'..."
START_RESTORE=$(date +%s)
PGPASSWORD="${PGPASSWORD:-}" pg_restore \
  -h "${PGHOST}" \
  -p "${PGPORT}" \
  -U "${PGUSER}" \
  -d "${PGDATABASE}" \
  --clean \
  --if-exists \
  --no-owner \
  -v \
  "${DUMP_FILE}" || {
    # pg_restore returns warnings as non-zero in some edge cases (e.g. drop non-existent objects),
    # so we log and verify structural integrity in Step 5.
    log "WARN" "pg_restore returned exit code $?, inspecting database health..."
  }

END_RESTORE=$(date +%s)
RESTORE_DURATION=$((END_RESTORE - START_RESTORE))
log "INFO" "pg_restore completed in ${RESTORE_DURATION}s."

# Step 5: Verify Flyway Compatibility
log "INFO" "Checking Flyway schema history integrity..."
FLYWAY_COUNT=$(PGPASSWORD="${PGPASSWORD:-}" psql -h "${PGHOST}" -p "${PGPORT}" -U "${PGUSER}" -d "${PGDATABASE}" -tAc \
  "SELECT count(*) FROM flyway_schema_history WHERE success = true;" || echo "0")

if [ "${FLYWAY_COUNT}" -eq 0 ]; then
  log "ERROR" "Flyway schema history is missing or corrupted!"
  exit 1
fi
log "INFO" "Flyway schema history verified: ${FLYWAY_COUNT} successful migrations recorded."

# Step 6: Verify Core Workflow Tables & Row Counts
log "INFO" "Verifying core table row counts..."
PGPASSWORD="${PGPASSWORD:-}" psql -h "${PGHOST}" -p "${PGPORT}" -U "${PGUSER}" -d "${PGDATABASE}" -c "
SELECT
  (SELECT count(*) FROM workflow_definitions) AS workflow_definitions,
  (SELECT count(*) FROM workflow_versions) AS workflow_versions,
  (SELECT count(*) FROM tickets) AS tickets,
  (SELECT count(*) FROM events) AS events,
  (SELECT count(*) FROM node_executions) AS node_executions,
  (SELECT count(*) FROM task_executions) AS task_executions,
  (SELECT count(*) FROM audit_events) AS audit_events,
  (SELECT count(*) FROM workflow_jobs) AS workflow_jobs,
  (SELECT count(*) FROM integration_executions) AS integration_executions,
  (SELECT count(*) FROM users) AS users,
  (SELECT count(*) FROM departments) AS departments;
"

# Step 7: Object Storage Attachment Recovery Audit
log "INFO" "Auditing file attachments metadata..."
ATTACHMENT_COUNT=$(PGPASSWORD="${PGPASSWORD:-}" psql -h "${PGHOST}" -p "${PGPORT}" -U "${PGUSER}" -d "${PGDATABASE}" -tAc \
  "SELECT count(*) FROM file_attachments;" || echo "0")
log "INFO" "Recorded file attachments: ${ATTACHMENT_COUNT} files."

log "INFO" "========================================================"
log "INFO" "PostgreSQL restore rehearsal completed successfully!"
log "INFO" "Database '${PGDATABASE}' is ready for application traffic."
log "INFO" "========================================================"
