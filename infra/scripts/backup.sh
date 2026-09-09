#!/usr/bin/env bash
# ==============================================================================
# Workflow Platform — Production PostgreSQL Backup Script
# ==============================================================================
# Performs a full logical database backup using pg_dump custom format (-Fc),
# computes SHA-256 checksum, and enforces retention pruning.
# ==============================================================================

set -euo pipefail

# Configuration defaults (can be overridden via environment)
PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"
PGDATABASE="${PGDATABASE:-workflow_platform}"
PGUSER="${PGUSER:-workflow}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/workflow-platform}"
RETENTION_DAYS="${RETENTION_DAYS:-7}"
TIMESTAMP="$(date -u +"%Y%m%d_%H%M%SZ")"
BACKUP_NAME="${PGDATABASE}_${TIMESTAMP}"
DUMP_FILE="${BACKUP_DIR}/${BACKUP_NAME}.dump"
CHECKSUM_FILE="${DUMP_FILE}.sha256"
METADATA_FILE="${BACKUP_DIR}/${BACKUP_NAME}.meta.json"

log() {
  local level="$1"
  local msg="$2"
  echo "{\"timestamp\":\"$(date -u +"%Y-%m-%dT%H:%M:%SZ")\",\"level\":\"${level}\",\"component\":\"backup\",\"message\":\"${msg}\"}"
}

log "INFO" "Starting PostgreSQL full backup for database '${PGDATABASE}' on ${PGHOST}:${PGPORT}..."

# Ensure target directory exists
mkdir -p "${BACKUP_DIR}"

START_TIME=$(date +%s)

# Execute pg_dump
# -Fc: Custom format (compressed, supports pg_restore selective restore and parallel workers)
# -b: Include large objects / blobs
# -v: Verbose output for logging
log "INFO" "Executing pg_dump into ${DUMP_FILE}..."
PGPASSWORD="${PGPASSWORD:-}" pg_dump \
  -h "${PGHOST}" \
  -p "${PGPORT}" \
  -U "${PGUSER}" \
  -d "${PGDATABASE}" \
  -Fc \
  -b \
  -f "${DUMP_FILE}"

# Compute SHA-256 checksum
log "INFO" "Computing SHA-256 checksum..."
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "${DUMP_FILE}" > "${CHECKSUM_FILE}"
elif command -v shasum >/dev/null 2>&1; then
  shasum -a 256 "${DUMP_FILE}" > "${CHECKSUM_FILE}"
else
  log "WARN" "Neither sha256sum nor shasum found; skipping checksum calculation"
fi

END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))
FILE_SIZE=$(stat -c%s "${DUMP_FILE}" 2>/dev/null || stat -f%z "${DUMP_FILE}" 2>/dev/null || wc -c < "${DUMP_FILE}")

# Write metadata summary
cat <<EOF > "${METADATA_FILE}"
{
  "backupName": "${BACKUP_NAME}",
  "database": "${PGDATABASE}",
  "host": "${PGHOST}",
  "port": ${PGPORT},
  "timestamp": "${TIMESTAMP}",
  "dumpFile": "${DUMP_FILE}",
  "sizeBytes": ${FILE_SIZE},
  "durationSeconds": ${DURATION},
  "format": "custom",
  "status": "SUCCESS"
}
EOF

log "INFO" "Backup completed successfully in ${DURATION}s. Size: ${FILE_SIZE} bytes. File: ${DUMP_FILE}"

# Apply Retention Policy: Prune backups older than RETENTION_DAYS
log "INFO" "Applying retention policy (pruning backups older than ${RETENTION_DAYS} days in ${BACKUP_DIR})..."
DELETED_COUNT=0
while IFS= read -r old_file; do
  if [ -n "${old_file}" ]; then
    rm -f "${old_file}" "${old_file}.sha256" "${old_file%.dump}.meta.json"
    DELETED_COUNT=$((DELETED_COUNT + 1))
  fi
done < <(find "${BACKUP_DIR}" -type f -name "${PGDATABASE}_*.dump" -mtime +"${RETENTION_DAYS}" 2>/dev/null || true)

log "INFO" "Retention pruning completed. ${DELETED_COUNT} old backup archive(s) removed."
