<#
.SYNOPSIS
    Phase 17.4E - Master Data Migration Runner (Stations & Trains).
    Migrates 8,994 stations and 4,514 trains from local PostgreSQL to Neon Production.
    Supports two independent execution stages:
      - Stage A: 'ExportOnly' (Local PostgreSQL -> Verified CSVs + Manifest)
      - Stage B: 'ImportOnly' (Manifest & CSVs -> Neon Production)
.NOTES
    STRICT SAFETY CONTROLS ENFORCED.
    - Architecture: Stage A (ExportOnly) and Stage B (ImportOnly) are strictly decoupled.
    - ExportOnly connects ONLY to local PostgreSQL; never connects to Neon.
    - ExportOnly preserves all verified CSV files and produces a SHA-256 manifest.json.
    - ImportOnly connects ONLY to Neon production; never connects to local PostgreSQL.
    - Hardened Manifest: Strictly validates filenames ('stations_master.csv', 'trains_master.csv'),
      rejects path traversal, validates metadata, schemas, row counts, min/max PKs, byte sizes, and SHA-256.
    - Schema Qualification: Explicitly sets 'SET search_path = public;' and references fully qualified
      'public.stations' and 'public.trains' across all SQL statements and sequence lookups.
    - Target Dependency Preflight: Verifies zero records exist in referencing child tables
      (coaches, seats, train_routes, train_stops, journeys, journey_stops, journey_seat_inventory, bookings).
    - Non-bypassable interactive confirmation: requires exact 'CONFIRM_MASTER_DATA' for ImportOnly.
    - Environment isolation: Snapshots and restores PGPASSWORD and PGSSLMODE on all exit paths.
    - Enforced SSL: PGSSLMODE=require for Neon production connection.
    - Concurrency protection: EXCLUSIVE table locks on public.stations and public.trains at transaction start.
    - Timeouts: lock_timeout='10s', statement_timeout='60s' prevent hangs and abort cleanly on lock contention.
    - Strict CSV validation: Strict UTF-8 decoding, quoted commas & embedded newlines (RFC-4180 / PostgreSQL COPY compliant),
      exact per-row field counts, duplicate PK rejection, null byte detection, and SHA-256 calculation.
    - Pre-setval Max PK assertion: Verifies max(station_id)=8997 and max(train_id)=4522 before sequence sync.
    - Transactional atomicity: Single atomic BEGIN ... COMMIT block with ON_ERROR_STOP on.
    - Pre-flight empty assertions on target tables before ingestion.
    - Post-load exact row count assertions (8,994 stations, 4,514 trains).
    - Sequence advancement: setval() synchronized to max(id). (Non-transactional acknowledged).
    - Sequence verification: Non-modifying check via currval() without consuming nextval().
    - Sequence recovery policy: Manual reset permitted ONLY if target table confirmed empty (0 rows) and zero concurrent writers exist. Never automated.
    - Zero data writes to users, bookings, payments, refunds, or outbox.
    - Zero local database modifications.
    - Redacted logging: Zero credentials or connection strings in output.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0, HelpMessage = "Mode of execution: 'ExportOnly' or 'ImportOnly'")]
    [ValidateSet('ExportOnly', 'ImportOnly')]
    [string]$Mode,

    [Parameter(Mandatory = $false, Position = 1, HelpMessage = "Path to staging directory containing exported CSV files and manifest")]
    [string]$StagingDir
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName 'Microsoft.VisualBasic'

function Format-RedactedText {
    param([string]$Text, [string]$Secret)
    if ([string]::IsNullOrEmpty($Text)) { return $Text }
    if (-not [string]::IsNullOrEmpty($Secret)) {
        return $Text -replace [regex]::Escape($Secret), '********'
    }
    return $Text
}

# Strict structural CSV validation helper (RFC-4180 & PostgreSQL COPY CSV compliant)
function Assert-VerifiedCsvStaging {
    param(
        [string]$CsvPath,
        [string[]]$ExpectedHeaders,
        [int]$ExpectedRowCount,
        [long]$ExpectedMinPk = 0,
        [long]$ExpectedMaxPk = 0,
        [string]$TableName
    )

    if (-not (Test-Path -LiteralPath $CsvPath -PathType Leaf)) {
        throw "Staging CSV file not found for ${TableName}: $CsvPath"
    }

    $fileInfo = Get-Item -LiteralPath $CsvPath
    if ($fileInfo.Length -eq 0) {
        throw "Staging CSV file for ${TableName} is empty (0 bytes): $CsvPath"
    }

    # 1. Byte-level null-byte audit
    $bytes = [System.IO.File]::ReadAllBytes($CsvPath)
    if ($bytes -contains 0) {
        throw "Binary corruption detected: Staging CSV for ${TableName} contains null bytes (0x00): $CsvPath"
    }

    # 2. Strict UTF-8 decoding validation (throws DecoderFallbackException on invalid bytes)
    $strictUtf8 = [System.Text.UTF8Encoding]::new($false, $true)
    try {
        [System.IO.File]::ReadAllText($CsvPath, $strictUtf8) | Out-Null
    } catch [System.Text.DecoderFallbackException] {
        throw "Strict UTF-8 decoding failed for ${TableName} ($CsvPath): invalid UTF-8 byte sequence detected."
    } catch {
        throw "File read failed for ${TableName} ($CsvPath): $($_.Exception.Message)"
    }

    # 3. RFC-4180 / PostgreSQL COPY CSV compliant structural parsing
    $parser = $null
    $minPk = [long]::MaxValue
    $maxPk = [long]::MinValue
    $seenPks = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)

    try {
        $parser = [Microsoft.VisualBasic.FileIO.TextFieldParser]::new($CsvPath, $strictUtf8)
        $parser.TextFieldType = [Microsoft.VisualBasic.FileIO.FieldType]::Delimited
        $parser.SetDelimiters(',')
        $parser.HasFieldsEnclosedInQuotes = $true
        $parser.TrimWhiteSpace = $false

        if ($parser.EndOfData) {
            throw "Staging CSV for ${TableName} is empty or contains zero rows."
        }

        # 4. Header validation
        $actualHeaders = $parser.ReadFields()
        if ($actualHeaders.Length -ne $ExpectedHeaders.Length) {
            throw "Header column count mismatch for ${TableName}. Expected $($ExpectedHeaders.Length) columns ($($ExpectedHeaders -join ', ')), got $($actualHeaders.Length) ($($actualHeaders -join ', '))."
        }
        for ($i = 0; $i -lt $ExpectedHeaders.Length; $i++) {
            if ($actualHeaders[$i] -ne $ExpectedHeaders[$i]) {
                throw "Header column [$i] mismatch for ${TableName}. Expected '$($ExpectedHeaders[$i])', got '$($actualHeaders[$i])'."
            }
        }

        # 5. Record-level validation
        $recordCount = 0

        while (-not $parser.EndOfData) {
            $fields = $parser.ReadFields()
            $recordCount++

            # Exact field count check for every single row
            if ($fields.Length -ne $ExpectedHeaders.Length) {
                throw "Exact field count mismatch on data row $($recordCount) in ${TableName}. Expected $($ExpectedHeaders.Length) fields, got $($fields.Length)."
            }

            # Primary key validation
            $pk = $fields[0]
            if ([string]::IsNullOrWhiteSpace($pk)) {
                throw "Data row $($recordCount) in ${TableName} has null or whitespace primary key."
            }

            # Duplicate PK rejection
            if (-not $seenPks.Add($pk)) {
                throw "Duplicate primary key '$pk' detected at data row $($recordCount) in ${TableName}."
            }

            [long]$pkVal = 0
            if (-not [long]::TryParse($pk, [ref]$pkVal)) {
                throw "Data row $($recordCount) in ${TableName} has non-integer primary key: '$pk'."
            }
            if ($pkVal -lt $minPk) { $minPk = $pkVal }
            if ($pkVal -gt $maxPk) { $maxPk = $pkVal }
        }

        # 6. Expected row count assertion
        if ($recordCount -ne $ExpectedRowCount) {
            throw "Parsed record count mismatch for ${TableName}. Expected $ExpectedRowCount rows, parsed $($recordCount) rows."
        }

        # 7. Expected min/max PK assertions
        if ($ExpectedMinPk -gt 0 -and $minPk -ne $ExpectedMinPk) {
            throw "Min PK ID mismatch for ${TableName}. Expected $ExpectedMinPk, parsed $minPk."
        }
        if ($ExpectedMaxPk -gt 0 -and $maxPk -ne $ExpectedMaxPk) {
            throw "Max PK ID mismatch for ${TableName}. Expected $ExpectedMaxPk, parsed $maxPk."
        }

    } finally {
        if ($null -ne $parser) {
            $parser.Close()
            $parser.Dispose()
        }
    }

    $hash = (Get-FileHash -LiteralPath $CsvPath -Algorithm SHA256).Hash
    return @{
        RowCount      = $recordCount
        UniquePkCount = $seenPks.Count
        MinPkId       = $minPk
        MaxPkId       = $maxPk
        Sha256        = $hash
        ByteSize      = $fileInfo.Length
    }
}

# Generates the authoritative cryptographic manifest for Stage A Export
function New-MigrationManifest {
    param(
        [Parameter(Mandatory = $true)]
        [hashtable]$StationsResult,

        [Parameter(Mandatory = $true)]
        [hashtable]$TrainsResult,

        [Parameter(Mandatory = $true)]
        [string]$ManifestPath
    )

    $manifestData = [ordered]@{
        schemaVersion      = "1.0"
        stage              = "Stage-A-ExportOnly"
        exportTimestampUtc = (Get-Date).ToUniversalTime().ToString("o")
        sourceDatabase     = "railway_booking"
        sourceHost         = "localhost"
        sourceUser         = "postgres"
        files              = [ordered]@{
            stations = [ordered]@{
                fileName      = "stations_master.csv"
                expectedTable = "public.stations"
                columns       = @('station_id', 'station_code', 'station_name', 'city', 'state')
                rowCount      = [int]$StationsResult.RowCount
                uniquePkCount = [int]$StationsResult.UniquePkCount
                minPkId       = [long]$StationsResult.MinPkId
                maxPkId       = [long]$StationsResult.MaxPkId
                sha256        = [string]$StationsResult.Sha256.ToUpperInvariant()
                byteSize      = [long]$StationsResult.ByteSize
            }
            trains = [ordered]@{
                fileName      = "trains_master.csv"
                expectedTable = "public.trains"
                columns       = @('train_id', 'train_number', 'train_name', 'status')
                rowCount      = [int]$TrainsResult.RowCount
                uniquePkCount = [int]$TrainsResult.UniquePkCount
                minPkId       = [long]$TrainsResult.MinPkId
                maxPkId       = [long]$TrainsResult.MaxPkId
                sha256        = [string]$TrainsResult.Sha256.ToUpperInvariant()
                byteSize      = [long]$TrainsResult.ByteSize
            }
        }
    }

    $manifestJson = $manifestData | ConvertTo-Json -Depth 5
    Set-Content -LiteralPath $ManifestPath -Value $manifestJson -Encoding UTF8
    return $manifestData
}

# Hardened manifest and path traversal verification helper
function Assert-VerifiedManifest {
    param(
        [string]$StagingDirectory
    )

    if ([string]::IsNullOrWhiteSpace($StagingDirectory) -or -not (Test-Path -LiteralPath $StagingDirectory -PathType Container)) {
        throw "StagingDir parameter must point to an existing directory: $StagingDirectory"
    }

    $resolvedStagingDir = [System.IO.Path]::GetFullPath($StagingDirectory)
    $normalizedStagingRoot = $resolvedStagingDir.TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
    $manifestFile = Join-Path $resolvedStagingDir 'manifest.json'
    if (-not (Test-Path -LiteralPath $manifestFile -PathType Leaf)) {
        throw "Cryptographic manifest 'manifest.json' not found in staging directory: $resolvedStagingDir"
    }

    try {
        $manifestContent = Get-Content -LiteralPath $manifestFile -Raw -Encoding UTF8
        $manifest = $manifestContent | ConvertFrom-Json
    } catch {
        throw "Failed to parse manifest.json as valid JSON: $($_.Exception.Message)"
    }

    # Top-level manifest validation
    if ($manifest.schemaVersion -ne '1.0') {
        throw "Manifest schemaVersion mismatch! Expected '1.0', got '$($manifest.schemaVersion)'."
    }
    if ($manifest.stage -ne 'Stage-A-ExportOnly') {
        throw "Manifest stage mismatch! Expected 'Stage-A-ExportOnly', got '$($manifest.stage)'."
    }
    if ($manifest.sourceDatabase -ne 'railway_booking') {
        throw "Manifest sourceDatabase mismatch! Expected 'railway_booking', got '$($manifest.sourceDatabase)'."
    }
    if (-not $manifest.files -or -not $manifest.files.stations -or -not $manifest.files.trains) {
        throw "Manifest files block missing required 'stations' or 'trains' entries."
    }

    $expectedSpecs = @{
        stations = @{
            ExactFileName  = 'stations_master.csv'
            ExpectedTable  = 'public.stations'
            ExpectedCols   = @('station_id', 'station_code', 'station_name', 'city', 'state')
            ExpectedRows   = 8994
            ExpectedMinPk  = 4
            ExpectedMaxPk  = 8997
        }
        trains = @{
            ExactFileName  = 'trains_master.csv'
            ExpectedTable  = 'public.trains'
            ExpectedCols   = @('train_id', 'train_number', 'train_name', 'status')
            ExpectedRows   = 4514
            ExpectedMinPk  = 1
            ExpectedMaxPk  = 4522
        }
    }

    $verifiedFiles = @{}

    foreach ($key in @('stations', 'trains')) {
        $spec = $expectedSpecs[$key]
        $fileEntry = $manifest.files.$key

        if ($null -eq $fileEntry) {
            throw "Manifest missing entry for '$key'."
        }

        # Filename and Path Traversal hardening
        $fileName = [string]$fileEntry.fileName
        if ($fileName -ne $spec.ExactFileName) {
            throw "Security violation: Manifest declares illegal fileName '$fileName' for $($key). Expected exact '$($spec.ExactFileName)'."
        }
        if ($fileName -match '[\\/:]' -or $fileName -match '\.\.') {
            throw "Security violation: Path traversal or invalid characters in fileName '$fileName'."
        }

        $csvPath = [System.IO.Path]::GetFullPath((Join-Path $resolvedStagingDir $fileName))
        if (-not $csvPath.StartsWith($normalizedStagingRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
            [System.IO.Path]::GetDirectoryName($csvPath).TrimEnd('\', '/') -ne $resolvedStagingDir.TrimEnd('\', '/')) {
            throw "Security violation: Staged CSV path '$csvPath' escapes staging root '$resolvedStagingDir'."
        }
        if (-not (Test-Path -LiteralPath $csvPath -PathType Leaf)) {
            throw "Staging CSV file not found on disk: $csvPath"
        }

        # Target Table name
        if ($fileEntry.expectedTable -ne $spec.ExpectedTable) {
            throw "Manifest expectedTable mismatch for $($key): expected '$($spec.ExpectedTable)', got '$($fileEntry.expectedTable)'."
        }

        # Column list
        $cols = @($fileEntry.columns)
        if ($cols.Count -ne $spec.ExpectedCols.Count) {
            throw "Manifest columns count mismatch for $($key): expected $($spec.ExpectedCols.Count), got $($cols.Count)."
        }
        for ($i = 0; $i -lt $spec.ExpectedCols.Count; $i++) {
            if ($cols[$i] -ne $spec.ExpectedCols[$i]) {
                throw "Manifest column [$i] mismatch for $($key): expected '$($spec.ExpectedCols[$i])', got '$($cols[$i])'."
            }
        }

        # Row counts & Boundaries
        if ([int]$fileEntry.rowCount -ne $spec.ExpectedRows) {
            throw "Manifest rowCount mismatch for $($key): expected $($spec.ExpectedRows), got $($fileEntry.rowCount)."
        }
        if ([int]$fileEntry.uniquePkCount -ne $spec.ExpectedRows) {
            throw "Manifest uniquePkCount mismatch for $($key): expected $($spec.ExpectedRows), got $($fileEntry.uniquePkCount)."
        }
        if ([long]$fileEntry.minPkId -ne $spec.ExpectedMinPk) {
            throw "Manifest minPkId mismatch for $($key): expected $($spec.ExpectedMinPk), got $($fileEntry.minPkId)."
        }
        if ([long]$fileEntry.maxPkId -ne $spec.ExpectedMaxPk) {
            throw "Manifest maxPkId mismatch for $($key): expected $($spec.ExpectedMaxPk), got $($fileEntry.maxPkId)."
        }

        # Byte size
        $diskLength = (Get-Item -LiteralPath $csvPath).Length
        if ([long]$fileEntry.byteSize -le 0 -or [long]$fileEntry.byteSize -ne $diskLength) {
            throw "Manifest byteSize mismatch for $($key): manifest records $($fileEntry.byteSize), disk length is $diskLength."
        }

        # SHA-256 validation
        $manifestSha = [string]$fileEntry.sha256
        if ($manifestSha -notmatch '^[A-Fa-f0-9]{64}$') {
            throw "Manifest sha256 for $($key) is not a valid 64-character hex hash: '$manifestSha'."
        }

        $actualSha = (Get-FileHash -LiteralPath $csvPath -Algorithm SHA256).Hash
        if ($actualSha -ne $manifestSha) {
            throw "Cryptographic hash mismatch for $($key) ($fileName)! Manifest: $manifestSha, Computed: $actualSha"
        }

        $verifiedFiles[$key] = @{
            Path     = $csvPath
            FileName = $fileName
            Sha256   = $actualSha
            RowCount = [int]$fileEntry.rowCount
            MinPkId  = [long]$fileEntry.minPkId
            MaxPkId  = [long]$fileEntry.maxPkId
            ByteSize = $diskLength
        }
    }

    return $verifiedFiles
}

# Snapshot caller environment before modifying connection state
$OriginalEnvSnapshot = @{
    PGPASSWORD = if (Test-Path 'env:PGPASSWORD') { $env:PGPASSWORD } else { $null }
    PGSSLMODE  = if (Test-Path 'env:PGSSLMODE')  { $env:PGSSLMODE }  else { $null }
}

try {
    # ---------------------------------------------------------------------------
    # COMMON GUARD: CLIENT BINARY VALIDATION
    # ---------------------------------------------------------------------------
    $PsqlBin = 'C:\Program Files\PostgreSQL\18\bin\psql.exe'
    if (-not (Test-Path $PsqlBin)) {
        throw "PostgreSQL client binary 'psql.exe' not found at expected path: $PsqlBin"
    }

    # ===========================================================================
    # STAGE A: EXPORT ONLY (Local PostgreSQL -> CSVs & Manifest)
    # ===========================================================================
    if ($Mode -eq 'ExportOnly') {
        Write-Host "=================================================================" -ForegroundColor Cyan
        Write-Host " STAGE A: EXPORT ONLY (Local PostgreSQL -> Staging CSVs & Manifest)" -ForegroundColor Cyan
        Write-Host "=================================================================" -ForegroundColor Cyan

        # Validate local database password
        $LocalPassword = if ($env:LOCAL_DB_PASSWORD) { $env:LOCAL_DB_PASSWORD } else { $env:DB_PASSWORD }
        if ([string]::IsNullOrWhiteSpace($LocalPassword)) {
            throw "Source database password is missing. Either LOCAL_DB_PASSWORD or DB_PASSWORD must be exported in session environment."
        }

        # Initialize staging directory
        if ([string]::IsNullOrWhiteSpace($StagingDir)) {
            $StagingDir = Join-Path ([System.IO.Path]::GetTempPath()) "railora_master_migration_$(Get-Date -Format 'yyyyMMdd_HHmmss')"
        }
        New-Item -ItemType Directory -Path $StagingDir -Force | Out-Null

        $resolvedStagingDir = [System.IO.Path]::GetFullPath($StagingDir)
        $StationsCsvPath    = Join-Path $resolvedStagingDir 'stations_master.csv'
        $TrainsCsvPath      = Join-Path $resolvedStagingDir 'trains_master.csv'
        $StationsCsvSql     = $StationsCsvPath.Replace('\', '/')
        $TrainsCsvSql       = $TrainsCsvPath.Replace('\', '/')
        $ExportScript       = Join-Path $resolvedStagingDir 'export_master_data.sql'
        $ManifestFile       = Join-Path $resolvedStagingDir 'manifest.json'

        Write-Host "[STAGE A.1] Exporting master stations and trains from local PostgreSQL..." -ForegroundColor Cyan

        $ExportSqlContent = @"
\set ON_ERROR_STOP on
\copy (SELECT station_id, station_code, station_name, city, state FROM stations ORDER BY station_id) TO '$StationsCsvSql' WITH (FORMAT CSV, HEADER true, ENCODING 'UTF8');
\copy (SELECT train_id, train_number, train_name, status FROM trains ORDER BY train_id) TO '$TrainsCsvSql' WITH (FORMAT CSV, HEADER true, ENCODING 'UTF8');
"@
        Set-Content -Path $ExportScript -Value $ExportSqlContent -Encoding UTF8

        $env:PGPASSWORD = $LocalPassword
        $exportOutput = & $PsqlBin -h localhost -p 5432 -U postgres -d railway_booking -v ON_ERROR_STOP=1 -f $ExportScript 2>&1
        $exportExit = $LASTEXITCODE

        # Remove temporary SQL script; preserve data CSVs
        Remove-Item -Path $ExportScript -Force -ErrorAction SilentlyContinue

        if ($exportExit -ne 0) {
            throw "Local export failed with exit code $($exportExit): $(Format-RedactedText $exportOutput $LocalPassword)"
        }

        Write-Host "[STAGE A.2] Validating exported CSV payloads..." -ForegroundColor Cyan

        $stationsResult = Assert-VerifiedCsvStaging -CsvPath $StationsCsvPath `
            -ExpectedHeaders @('station_id', 'station_code', 'station_name', 'city', 'state') `
            -ExpectedRowCount 8994 `
            -ExpectedMinPk 4 `
            -ExpectedMaxPk 8997 `
            -TableName 'stations'

        $trainsResult = Assert-VerifiedCsvStaging -CsvPath $TrainsCsvPath `
            -ExpectedHeaders @('train_id', 'train_number', 'train_name', 'status') `
            -ExpectedRowCount 4514 `
            -ExpectedMinPk 1 `
            -ExpectedMaxPk 4522 `
            -TableName 'trains'

        Write-Host "  -> Verified stations CSV: $($stationsResult.RowCount) rows (Min PK: $($stationsResult.MinPkId), Max PK: $($stationsResult.MaxPkId), SHA256: $($stationsResult.Sha256))" -ForegroundColor Green
        Write-Host "  -> Verified trains CSV  : $($trainsResult.RowCount) rows (Min PK: $($trainsResult.MinPkId), Max PK: $($trainsResult.MaxPkId), SHA256: $($trainsResult.Sha256))" -ForegroundColor Green

        Write-Host "[STAGE A.3] Generating cryptographic manifest..." -ForegroundColor Cyan

        $manifestData = New-MigrationManifest -StationsResult $stationsResult -TrainsResult $trainsResult -ManifestPath $ManifestFile

        Write-Host "`n-----------------------------------------------------------------" -ForegroundColor Green
        Write-Host " STAGE A (EXPORT ONLY) COMPLETE - CSV FILES PRESERVED" -ForegroundColor Green
        Write-Host "-----------------------------------------------------------------" -ForegroundColor Green
        Write-Host " Staging Directory : $resolvedStagingDir" -ForegroundColor White
        Write-Host " Manifest File     : $ManifestFile" -ForegroundColor White
        Write-Host " Stations CSV      : $StationsCsvPath ($($stationsResult.ByteSize) bytes)" -ForegroundColor White
        Write-Host " Trains CSV        : $TrainsCsvPath ($($trainsResult.ByteSize) bytes)" -ForegroundColor White
        Write-Host "-----------------------------------------------------------------" -ForegroundColor Green
        Write-Host "`nTo perform Stage B (Neon Import), run:" -ForegroundColor Yellow
        Write-Host "  .\scripts\phase-17-4e-master-data-migration.ps1 -Mode ImportOnly -StagingDir '$resolvedStagingDir'`n" -ForegroundColor Cyan
        return
    }

    # ===========================================================================
    # STAGE B: IMPORT ONLY (Verified CSVs & Manifest -> Neon Production)
    # ===========================================================================
    if ($Mode -eq 'ImportOnly') {
        Write-Host "=================================================================" -ForegroundColor Cyan
        Write-Host " STAGE B: IMPORT ONLY (Verified Staging CSVs -> Neon Production)" -ForegroundColor Cyan
        Write-Host "=================================================================" -ForegroundColor Cyan

        # 1. Hardened manifest and path traversal validation
        Write-Host "[STAGE B.1] Inspecting and authenticating manifest & paths..." -ForegroundColor Cyan
        $verifiedFiles = Assert-VerifiedManifest -StagingDirectory $StagingDir
        Write-Host "  -> Manifest authenticity, metadata, schema, and path safety verified." -ForegroundColor Green

        $StationsCsvPath = $verifiedFiles.stations.Path
        $TrainsCsvPath   = $verifiedFiles.trains.Path

        # 2. Comprehensive structural re-validation of CSV contents
        Write-Host "[STAGE B.2] Performing strict structural and invariant CSV audit..." -ForegroundColor Cyan

        $stationsResult = Assert-VerifiedCsvStaging -CsvPath $StationsCsvPath `
            -ExpectedHeaders @('station_id', 'station_code', 'station_name', 'city', 'state') `
            -ExpectedRowCount 8994 `
            -ExpectedMinPk 4 `
            -ExpectedMaxPk 8997 `
            -TableName 'stations'

        $trainsResult = Assert-VerifiedCsvStaging -CsvPath $TrainsCsvPath `
            -ExpectedHeaders @('train_id', 'train_number', 'train_name', 'status') `
            -ExpectedRowCount 4514 `
            -ExpectedMinPk 1 `
            -ExpectedMaxPk 4522 `
            -TableName 'trains'

        Write-Host "  -> Stations audit: $($stationsResult.RowCount) data rows, Unique PKs: $($stationsResult.UniquePkCount), Max PK: $($stationsResult.MaxPkId)" -ForegroundColor Green
        Write-Host "  -> Trains audit  : $($trainsResult.RowCount) data rows, Unique PKs: $($trainsResult.UniquePkCount), Max PK: $($trainsResult.MaxPkId)" -ForegroundColor Green

        # 3. Target connection parameters validation
        $NeonHost = $env:NEON_DB_HOST
        $NeonPort = $env:NEON_DB_PORT
        $NeonUser = $env:NEON_DB_USER
        $NeonPass = $env:NEON_DB_PASSWORD
        $NeonDb   = $env:NEON_DB_NAME

        $missingVars = @()
        if ([string]::IsNullOrWhiteSpace($NeonHost)) { $missingVars += 'NEON_DB_HOST' }
        if ([string]::IsNullOrWhiteSpace($NeonPort)) { $missingVars += 'NEON_DB_PORT' }
        if ([string]::IsNullOrWhiteSpace($NeonUser)) { $missingVars += 'NEON_DB_USER' }
        if ([string]::IsNullOrWhiteSpace($NeonPass)) { $missingVars += 'NEON_DB_PASSWORD' }
        if ([string]::IsNullOrWhiteSpace($NeonDb))   { $missingVars += 'NEON_DB_NAME' }

        if ($missingVars.Count -gt 0) {
            throw "Target connection variable(s) missing from session environment: $($missingVars -join ', ')."
        }

        if ($NeonHost -notmatch '^[a-z0-9-]+(?:\.c-\d+)?\.ap-southeast-1\.aws\.neon\.tech$') {
            throw "Target host violates strict direct Neon ap-southeast-1 regex policy (got: '$NeonHost')."
        }
        if ($NeonHost -like '*-pooler*') {
            throw "Pooler endpoints (-pooler) are strictly forbidden for master data migration."
        }
        if ($NeonDb -ne 'railway_booking_prod') {
            throw "Target database must be 'railway_booking_prod', got '$NeonDb'."
        }
        if ($NeonUser -ne 'neondb_owner') {
            throw "Target role must be 'neondb_owner', got '$NeonUser'."
        }
        if ([int]$NeonPort -ne 5432) {
            throw "Target port must be 5432, got '$NeonPort'."
        }

        # 4. Target Migration Summary & Safety Invariants
        Write-Host "`n-----------------------------------------------------------------" -ForegroundColor Yellow
        Write-Host " TARGET MIGRATION SUMMARY & SAFETY INVARIANTS" -ForegroundColor Yellow
        Write-Host "-----------------------------------------------------------------" -ForegroundColor Yellow
        Write-Host " Target Cloud Host : $NeonHost" -ForegroundColor White
        Write-Host " Target Database   : $NeonDb" -ForegroundColor White
        Write-Host " Target Role       : $NeonUser" -ForegroundColor White
        Write-Host " Port              : $NeonPort" -ForegroundColor White
        Write-Host " SSL Mode          : require (Enforced via PGSSLMODE)" -ForegroundColor White
        Write-Host " Schema Scope      : public (Enforced via search_path & full qualification)" -ForegroundColor White
        Write-Host " Password Status   : [PRESENT - REDACTED]" -ForegroundColor White
        Write-Host " Staging Directory : $StagingDir" -ForegroundColor White
        Write-Host " Stations Payload  : 8,994 verified rows (IDs 4 to 8,997, SHA256: $($stationsResult.Sha256))" -ForegroundColor White
        Write-Host " Trains Payload    : 4,514 verified rows (IDs 1 to 4,522, SHA256: $($trainsResult.Sha256))" -ForegroundColor White
        Write-Host " Transaction Scope : Single atomic BEGIN ... COMMIT block" -ForegroundColor White
        Write-Host " Concurrency Lock  : EXCLUSIVE on public.stations, public.trains" -ForegroundColor White
        Write-Host " Timeout Guards    : lock_timeout='10s', statement_timeout='60s'" -ForegroundColor White
        Write-Host " Dependency Guard  : Pre-flight check confirms 0 records in 8 child tables" -ForegroundColor White
        Write-Host " Sensitive Tables  : EXCLUDED (0 writes to users, bookings, payments, outbox)" -ForegroundColor White
        Write-Host " Pre-setval Max PK : Assert max(station_id)=8997 and max(train_id)=4522" -ForegroundColor White
        Write-Host " Sequence Note     : setval() is non-transactional in PostgreSQL" -ForegroundColor White
        Write-Host " Sequence Policy   : Manual reset ONLY if table confirmed 0 rows & zero active writers" -ForegroundColor White
        Write-Host "-----------------------------------------------------------------" -ForegroundColor Yellow

        # 5. Non-bypassable interactive confirmation: exact input required
        $confirm = Read-Host "`nType 'CONFIRM_MASTER_DATA' to execute transactional load to Neon production"
        if ($confirm -ne 'CONFIRM_MASTER_DATA') {
            Write-Warning "Migration cancelled by user. Zero production writes executed."
            return
        }

        Write-Host "`n[STAGE B.3] Executing transactional ingestion to Neon production..." -ForegroundColor Cyan

        $StationsCsvSql = $StationsCsvPath.Replace('\', '/')
        $TrainsCsvSql   = $TrainsCsvPath.Replace('\', '/')
        $ImportScript   = Join-Path $StagingDir 'import_master_data.sql'

        $ImportSqlContent = @"
\set ON_ERROR_STOP on

-- Explicitly enforce public schema search_path
SET search_path = public;

-- Strict timeout safeguards: abort cleanly if lock cannot be acquired or query hangs
SET lock_timeout = '10s';
SET statement_timeout = '60s';

BEGIN;

-- 1. Concurrent-write protection: Acquire EXCLUSIVE table locks on fully qualified target tables
-- Mode: EXCLUSIVE blocks all concurrent modifications (INSERT, UPDATE, DELETE)
-- and SELECT FOR UPDATE/SHARE, while permitting concurrent read-only queries (ACCESS SHARE).
-- If locks cannot be acquired within 10s (lock_timeout), statement cancels and transaction rolls back cleanly.
-- Exactly targets public.stations and public.trains; no unrelated tables are locked or modified.
LOCK TABLE public.stations, public.trains IN EXCLUSIVE MODE;

-- 2. Pre-flight dependency safety guard: Verify zero child records exist in referencing downstream tables
DO `$`$
BEGIN
    IF (SELECT count(*) FROM public.coaches) <> 0 OR
       (SELECT count(*) FROM public.seats) <> 0 OR
       (SELECT count(*) FROM public.train_routes) <> 0 OR
       (SELECT count(*) FROM public.train_stops) <> 0 OR
       (SELECT count(*) FROM public.journeys) <> 0 OR
       (SELECT count(*) FROM public.journey_stops) <> 0 OR
       (SELECT count(*) FROM public.journey_seat_inventory) <> 0 OR
       (SELECT count(*) FROM public.bookings) <> 0 THEN
        RAISE EXCEPTION 'Dependency safety check failed: Downstream child tables contain existing records! Aborting import.';
    END IF;
END `$`$;

-- 3. Pre-flight zero-row target safety guard: Target tables MUST be empty immediately before import
DO `$`$
BEGIN
    IF (SELECT count(*) FROM public.stations) <> 0 THEN
        RAISE EXCEPTION 'Safety check failed: public.stations is not empty (row count: %)', (SELECT count(*) FROM public.stations);
    END IF;
    IF (SELECT count(*) FROM public.trains) <> 0 THEN
        RAISE EXCEPTION 'Safety check failed: public.trains is not empty (row count: %)', (SELECT count(*) FROM public.trains);
    END IF;
END `$`$;

-- 4. Stream data into target tables via client-side \copy within open transaction
\copy public.stations (station_id, station_code, station_name, city, state) FROM '$StationsCsvSql' WITH (FORMAT CSV, HEADER true, ENCODING 'UTF8');
\copy public.trains (train_id, train_number, train_name, status) FROM '$TrainsCsvSql' WITH (FORMAT CSV, HEADER true, ENCODING 'UTF8');

-- 5. Post-load assertion: Verify exact row counts
DO `$`$
BEGIN
    IF (SELECT count(*) FROM public.stations) <> 8994 THEN
        RAISE EXCEPTION 'Post-load row count mismatch on public.stations! Expected 8994, got %', (SELECT count(*) FROM public.stations);
    END IF;
    IF (SELECT count(*) FROM public.trains) <> 4514 THEN
        RAISE EXCEPTION 'Post-load row count mismatch on public.trains! Expected 4514, got %', (SELECT count(*) FROM public.trains);
    END IF;
END `$`$;

-- 6. Pre-setval assertion: Verify actual maximum primary-key IDs match expected sequence targets
DO `$`$
BEGIN
    IF (SELECT max(station_id) FROM public.stations) <> 8997 THEN
        RAISE EXCEPTION 'Max PK verification failed for public.stations: max(station_id) is % (expected 8997)',
            (SELECT max(station_id) FROM public.stations);
    END IF;
    IF (SELECT max(train_id) FROM public.trains) <> 4522 THEN
        RAISE EXCEPTION 'Max PK verification failed for public.trains: max(train_id) is % (expected 4522)',
            (SELECT max(train_id) FROM public.trains);
    END IF;
END `$`$;

-- 7. Identity sequence synchronization (NOTE: setval() is non-transactional in PostgreSQL)
SELECT setval(pg_get_serial_sequence('public.stations', 'station_id'), (SELECT max(station_id) FROM public.stations), true);
SELECT setval(pg_get_serial_sequence('public.trains', 'train_id'), (SELECT max(train_id) FROM public.trains), true);

-- 8. Non-modifying sequence verification
DO `$`$
BEGIN
    IF currval(pg_get_serial_sequence('public.stations', 'station_id')) <> 8997 THEN
        RAISE EXCEPTION 'Sequence verification failed for public.stations: currval is % (expected 8997)', 
            currval(pg_get_serial_sequence('public.stations', 'station_id'));
    END IF;
    IF currval(pg_get_serial_sequence('public.trains', 'train_id')) <> 4522 THEN
        RAISE EXCEPTION 'Sequence verification failed for public.trains: currval is % (expected 4522)', 
            currval(pg_get_serial_sequence('public.trains', 'train_id'));
    END IF;
END `$`$;

COMMIT;
"@
        Set-Content -Path $ImportScript -Value $ImportSqlContent -Encoding UTF8

        $env:PGPASSWORD = $NeonPass
        $env:PGSSLMODE  = 'require'

        $importOutput = & $PsqlBin -h $NeonHost -p $NeonPort -U $NeonUser -d $NeonDb -v ON_ERROR_STOP=1 -f $ImportScript 2>&1
        $importExit = $LASTEXITCODE

        # Remove temporary SQL script; preserve data CSVs and manifest
        Remove-Item -Path $ImportScript -Force -ErrorAction SilentlyContinue

        if ($importExit -ne 0) {
            throw "Neon transactional import failed with exit code $($importExit): $(Format-RedactedText $importOutput $NeonPass)"
        }

        Write-Host "`n[SUCCESS] Master data migration committed cleanly to Neon Production!" -ForegroundColor Green
        return
    }

} finally {
    # ---------------------------------------------------------------------------
    # ENVIRONMENT RESTORATION
    # ---------------------------------------------------------------------------
    if ($OriginalEnvSnapshot) {
        foreach ($entry in $OriginalEnvSnapshot.GetEnumerator()) {
            $varName = $entry.Key
            $varVal  = $entry.Value
            if ($null -ne $varVal) {
                Set-Item -Path "env:$varName" -Value $varVal
            } else {
                if (Test-Path "env:$varName") {
                    Remove-Item -Path "env:$varName"
                }
            }
        }
    }
}
