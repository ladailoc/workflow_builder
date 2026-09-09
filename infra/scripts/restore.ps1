# ==============================================================================
# Workflow Platform — Windows PowerShell PostgreSQL Restore Script
# ==============================================================================
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)]
    [string]$DumpFile,
    [string]$Database = $env:PGDATABASE ?? "workflow_platform",
    [string]$HostName = $env:PGHOST ?? "localhost",
    [int]$Port = [int]($env:PGPORT ?? 5432),
    [string]$User = $env:PGUSER ?? "workflow"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $DumpFile)) {
    Write-Error "Backup dump file '$DumpFile' not found."
    exit 1
}

$ChecksumFile = "${DumpFile}.sha256"
if (Test-Path $ChecksumFile) {
    Write-Host "[INFO] Verifying SHA-256 checksum..."
    $ExpectedHash = (Get-Content $ChecksumFile).Split(" ")[0].Trim().ToUpper()
    $ActualHash = (Get-FileHash -Path $DumpFile -Algorithm SHA256).Hash.ToUpper()
    if ($ExpectedHash -ne $ActualHash) {
        Write-Error "SHA-256 Checksum mismatch! Expected: $ExpectedHash, Actual: $ActualHash"
        exit 1
    }
    Write-Host "[INFO] Checksum verified: $ActualHash"
}

Write-Host "[INFO] Terminating active connections to $Database..."
& psql -h $HostName -p $Port -U $User -d postgres -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$Database' AND pid <> pg_backend_pid();"

Write-Host "[INFO] Restoring database $Database from $DumpFile..."
$StartTime = Get-Date
& pg_restore -h $HostName -p $Port -U $User -d $Database --clean --if-exists --no-owner -v $DumpFile
$Duration = ((Get-Date) - $StartTime).TotalSeconds
Write-Host "[INFO] pg_restore completed in $([math]::Round($Duration, 2))s."

# Check Flyway
$FlywayCount = & psql -h $HostName -p $Port -U $User -d $Database -tAc "SELECT count(*) FROM flyway_schema_history WHERE success = true;"
Write-Host "[INFO] Flyway schema history migrations verified: $FlywayCount"
