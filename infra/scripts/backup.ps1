# ==============================================================================
# Workflow Platform — Windows PowerShell PostgreSQL Backup Script
# ==============================================================================
[CmdletBinding()]
param(
    [string]$HostName = $env:PGHOST ?? "localhost",
    [int]$Port = [int]($env:PGPORT ?? 5432),
    [string]$Database = $env:PGDATABASE ?? "workflow_platform",
    [string]$User = $env:PGUSER ?? "workflow",
    [string]$BackupDir = $env:BACKUP_DIR ?? "C:\var\backups\workflow-platform",
    [int]$RetentionDays = 7
)

$ErrorActionPreference = "Stop"
$Timestamp = (Get-Date).ToUniversalTime().ToString("yyyyMMdd_HHmmssZ")
$BackupName = "${Database}_${Timestamp}"
$DumpFile = Join-Path $BackupDir "${BackupName}.dump"
$ChecksumFile = "${DumpFile}.sha256"

if (-not (Test-Path $BackupDir)) {
    New-Item -ItemType Directory -Path $BackupDir -Force | Out-Null
}

Write-Host "[INFO] Starting PostgreSQL backup for $Database on ${HostName}:${Port}..."
$StartTime = Get-Date

& pg_dump -h $HostName -p $Port -U $User -d $Database -Fc -b -f $DumpFile

if ($LASTEXITCODE -ne 0) {
    Write-Error "pg_dump failed with exit code $LASTEXITCODE"
    exit 1
}

$Hash = (Get-FileHash -Path $DumpFile -Algorithm SHA256).Hash
"${Hash}  $([System.IO.Path]::GetFileName($DumpFile))" | Out-File -FilePath $ChecksumFile -Encoding ascii

$Duration = ((Get-Date) - $StartTime).TotalSeconds
$FileSize = (Get-Item $DumpFile).Length

Write-Host "[INFO] Backup completed successfully in $([math]::Round($Duration, 2))s. Size: $FileSize bytes. File: $DumpFile"

# Prune old backups
$Cutoff = (Get-Date).AddDays(-$RetentionDays)
Get-ChildItem -Path $BackupDir -Filter "${Database}_*.dump" | Where-Object { $_.LastWriteTime -lt $Cutoff } | ForEach-Object {
    Write-Host "[INFO] Pruning old backup: $($_.FullName)"
    Remove-Item $_.FullName -Force -ErrorAction SilentlyContinue
    Remove-Item "$($_.FullName).sha256" -Force -ErrorAction SilentlyContinue
}
