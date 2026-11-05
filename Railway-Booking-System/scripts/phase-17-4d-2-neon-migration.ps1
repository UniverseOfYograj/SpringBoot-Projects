# ============================================================================
# Railora Railway Booking System: Controlled Neon Migration Execution Script
# Phase: 17.4D.2A — Controlled Migration Execution (Remediated Final)
# File: scripts/phase-17-4d-2-neon-migration.ps1
#
# OBJECTIVE:
# Executes one-time Spring Boot migration-only startup against Neon PostgreSQL
# to apply Flyway migrations V1 through V7 and run Hibernate ddl-auto=validate.
#
# CERTIFIED SAFETY INVARIANTS:
# 1. Structural URL validation: direct endpoint only (no -pooler), AWS Singapore
#    region (*.ap-southeast-1.aws.neon.tech), port 5432, db 'railway_booking_prod',
#    and strictly one sslmode=require parameter without conflicting SSL flags.
# 2. Localhost, 127.0.0.1, or local database targets are strictly rejected.
# 3. Role must be exactly 'neondb_owner'.
# 4. Sanitizes all ambient PostgreSQL and Spring Datasource environment variables.
# 5. Database credentials injected exclusively via validated session environment
#    variables ($env:DB_URL, $env:DB_USERNAME, $env:DB_PASSWORD), completely
#    preventing shell command-splitting on '&' in JDBC query parameters.
# 6. Never prints passwords or credentials in terminal, logs, or error streams.
# 7. Redacts database password from all stdout/stderr real-time event streams.
# 8. Arguments correctly quoted for Windows cmd.exe / ProcessStartInfo execution.
# 9. Bounded execution lifecycle with configurable timeout; uses taskkill.exe /T /F
#    targeted strictly to the runner's own process tree.
# 10. Precise process ownership & orphan termination: discovers and terminates only
#     verified descendant processes linked via parent-child lineage (ParentProcessId);
#     handles active root, exited root with orphan descendants, and verified child trees.
#     Never terminates unrelated processes or kills by executable name.
# 11. Dual-gate verification: monitors BOTH Flyway completion AND Hibernate schema
#     validation; strictly requires clean process exit code 0.
# 12. Zero automatic retry; zero automatic flyway repair.
# 13. Server rollback awareness: client termination does not prove server rollback
#     has completed; mandates pg_stat_activity session verification before any subsequent action.
# 14. Environment snapshot & complete restoration: captures pre-execution environment
#     state across all managed, modified, or ambient PG*, POSTGRES_*, and
#     SPRING_DATASOURCE_* variables and restores them in an outer finally block
#     on all exit paths (success, failure, timeout, exceptions).
# ============================================================================

[CmdletBinding()]
param (
    [Parameter(Mandatory = $false, HelpMessage = 'Direct Neon JDBC URL. If omitted, uses $env:DB_URL')]
    [string]$DbUrl = $env:DB_URL,

    [Parameter(Mandatory = $false, HelpMessage = 'Neon Database Role. If omitted, uses $env:DB_USERNAME')]
    [string]$DbUsername = $env:DB_USERNAME,

    [Parameter(Mandatory = $false, HelpMessage = 'Neon Database Password. If omitted, uses $env:DB_PASSWORD')]
    [string]$DbPassword = $env:DB_PASSWORD,

    [Parameter(Mandatory = $false, HelpMessage = 'Execution timeout in seconds (default: 180)')]
    [int]$TimeoutSeconds = 180,

    [Parameter(Mandatory = $false, HelpMessage = 'Bypass interactive prompt (safety gates still strictly enforced)')]
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

# Redaction helper to protect credentials in streaming output and error logs
function Format-RedactedText {
    param(
        [string]$Text,
        [string]$Secret
    )
    if ([string]::IsNullOrEmpty($Text)) { return $Text }
    if (-not [string]::IsNullOrEmpty($Secret)) {
        return $Text -replace [regex]::Escape($Secret), '********'
    }
    return $Text
}

# Protected Windows system process IDs and process names that must never be targeted
$GlobalProtectedPids = @(0, 4)
$GlobalProtectedNames = @('System', 'Registry', 'smss.exe', 'csrss.exe', 'wininit.exe', 'services.exe', 'lsass.exe', 'svchost.exe', 'winlogon.exe', 'dwm.exe', 'explorer.exe', 'fontdrvhost.exe')

# Strict verification of the migration runner root process launched by this script
function Assert-VerifiedMigrationRoot {
    param(
        [int]$ProcessId,
        [datetime]$LaunchTimeUtc,
        [string]$ExpectedRepoRoot
    )

    if ($ProcessId -le 4 -or $GlobalProtectedPids -contains $ProcessId) {
        throw "CRITICAL SECURITY FAULT: Process ID $ProcessId is a protected system process. Root validation rejected."
    }

    $cimProc = $null
    for ($attempt = 0; $attempt -lt 5; $attempt++) {
        $cimProc = Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId" -ErrorAction SilentlyContinue
        if ($cimProc) { break }
        Start-Sleep -Milliseconds 50
    }

    if (-not $cimProc) {
        $liveCheck = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
        if (-not $liveCheck) {
            throw "CRITICAL FAULT: Launched root process PID $ProcessId exited prematurely before CIM verification could complete."
        }
        throw "CRITICAL FAULT: Unable to retrieve CIM process information for launched root PID $ProcessId."
    }

    # 1. Verify executable is cmd.exe
    if ($cimProc.Name -ne 'cmd.exe') {
        throw "CRITICAL SECURITY FAULT: Launched root process PID $ProcessId is '$($cimProc.Name)', expected 'cmd.exe'."
    }

    # 2. Check for protected process names
    if ($GlobalProtectedNames -contains $cimProc.Name) {
        throw "CRITICAL SECURITY FAULT: Process PID $ProcessId matches protected OS process name '$($cimProc.Name)'."
    }

    # 3. Verify command line binds to the exact Railora project directory and Maven wrapper invocation
    $expectedMvnw = Join-Path $ExpectedRepoRoot 'mvnw.cmd'
    if ($cimProc.CommandLine -notmatch [regex]::Escape($expectedMvnw)) {
        throw "CRITICAL SECURITY FAULT: Process PID $ProcessId command line does not bind to expected repository wrapper '$expectedMvnw': $($cimProc.CommandLine)"
    }

    if ($cimProc.CommandLine -notmatch 'spring-boot:run' -or $cimProc.CommandLine -notmatch '-Dspring-boot\.run\.arguments=') {
        throw "CRITICAL SECURITY FAULT: Process PID $ProcessId command line does not match expected Maven spring-boot:run invocation: $($cimProc.CommandLine)"
    }

    # 4. Verify process creation time falls within the runner execution window
    $liveProc = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    $procStartTime = if ($liveProc) { try { $liveProc.StartTime } catch { $null } } else { $null }
    if (-not $procStartTime -and $cimProc.CreationDate) {
        $procStartTime = $cimProc.CreationDate
    }

    if ($procStartTime) {
        $elapsedSinceLaunch = [Math]::Abs(([DateTime]::UtcNow - $procStartTime.ToUniversalTime()).TotalSeconds)
        if ($elapsedSinceLaunch -gt 15) {
            throw "CRITICAL SECURITY FAULT: Process PID $ProcessId creation time ($procStartTime) falls outside runner launch window."
        }
    }

    return @{
        Id = $ProcessId
        Name = $cimProc.Name
        CommandLine = $cimProc.CommandLine
        StartTime = $procStartTime
        CreationDate = $cimProc.CreationDate
        Verified = $true
    }
}

# Revalidates process identity to detect PID reuse before termination
function Test-IsProcessRunningWithSameIdentity {
    param(
        [hashtable]$IdentityRecord
    )
    if (-not $IdentityRecord -or -not $IdentityRecord.Id -or $IdentityRecord.Id -le 4) {
        return $false
    }
    $liveProc = Get-Process -Id $IdentityRecord.Id -ErrorAction SilentlyContinue
    if (-not $liveProc) { return $false }

    # 1. Executable name check
    $expectedBaseName = $IdentityRecord.Name -replace '\.exe$', ''
    if ($liveProc.ProcessName -ne $expectedBaseName) {
        return $false
    }

    # 2. StartTime check for PID reuse
    if ($IdentityRecord.StartTime) {
        try {
            $diffSeconds = [Math]::Abs(($liveProc.StartTime - $IdentityRecord.StartTime).TotalSeconds)
            if ($diffSeconds -gt 2) {
                return $false
            }
        } catch {
            return $false
        }
    } elseif ($IdentityRecord.CreationDate) {
        $cim = Get-CimInstance Win32_Process -Filter "ProcessId = $($IdentityRecord.Id)" -ErrorAction SilentlyContinue
        if (-not $cim -or $cim.CreationDate -ne $IdentityRecord.CreationDate) {
            return $false
        }
    }

    return $true
}

# Dynamic descendant discovery through strictly verified lineage (multi-pass recursive resolution)
function Update-TrackedMigrationDescendants {
    param(
        [hashtable]$RootIdentity,
        [System.Collections.IDictionary]$TrackedDescendants
    )
    if (-not $RootIdentity -or $RootIdentity.Verified -ne $true -or $RootIdentity.Id -le 4) {
        return
    }

    # Allowed executables in Railora Maven/Java process tree
    $allowedChildNames = @('cmd.exe', 'conhost.exe', 'mvnw.cmd', 'java.exe', 'powershell.exe', 'OpenConsole.exe')

    $allProcs = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue
    if (-not $allProcs) { return }

    # Multi-pass recursive discovery: iteratively resolve child processes at arbitrary depth
    $addedNew = $true
    while ($addedNew) {
        $addedNew = $false

        $knownParentPids = [System.Collections.Generic.HashSet[int]]::new()
        [void]$knownParentPids.Add($RootIdentity.Id)
        foreach ($k in $TrackedDescendants.Keys) {
            [void]$knownParentPids.Add($k)
        }

        foreach ($cand in $allProcs) {
            $cid = [int]$cand.ProcessId
            if ($TrackedDescendants.ContainsKey($cid)) { continue }
            if ($knownParentPids.Contains([int]$cand.ParentProcessId)) {
                if ($cid -le 4 -or $GlobalProtectedPids -contains $cid) { continue }
                if ($GlobalProtectedNames -contains $cand.Name) { continue }
                if ($allowedChildNames -notcontains $cand.Name) { continue }

                # Verify creation time is not older than root launch
                if ($cand.CreationDate -and $RootIdentity.CreationDate -and ($cand.CreationDate -lt $RootIdentity.CreationDate.AddSeconds(-2))) {
                    continue
                }

                $liveP = Get-Process -Id $cid -ErrorAction SilentlyContinue
                $startTime = if ($liveP) { try { $liveP.StartTime } catch { $null } } else { $null }
                $TrackedDescendants[$cid] = [PSCustomObject]@{
                    Id = $cid
                    ParentId = [int]$cand.ParentProcessId
                    Name = $cand.Name
                    CreationDate = $cand.CreationDate
                    StartTime = $startTime
                    CommandLine = $cand.CommandLine
                }
                $addedNew = $true
            }
        }
    }
}

# Precise process-tree termination helper with strict ownership validation and anti-PID reuse checks
function Stop-MigrationProcessTree {
    param(
        [hashtable]$RootIdentity,
        [string]$Context = 'TERMINATION',
        [string]$Secret = '',
        [System.Collections.IDictionary]$TrackedDescendants = $null,
        [string]$TaskkillPath = "$env:SystemRoot\System32\taskkill.exe"
    )

    # Enforce production taskkill executable fixed to Windows System32 unless test hook explicitly authorized
    if ($TaskkillPath -ne "$env:SystemRoot\System32\taskkill.exe") {
        if ($env:RAILORA_TEST_ALLOW_TASKKILL_STUB -ne '1') {
            $TaskkillPath = "$env:SystemRoot\System32\taskkill.exe"
        }
    }

    # Strict Root Validation: Reject null, unverified, or protected system PIDs
    if (-not $RootIdentity -or $RootIdentity.Verified -ne $true -or $RootIdentity.Id -le 4 -or $GlobalProtectedPids -contains $RootIdentity.Id) {
        Write-Host "[$Context] CRITICAL: Root process identity is missing, unverified, or protected (PID: $($RootIdentity.Id)). Refusing process-tree discovery or termination." -ForegroundColor Red
        return $false
    }

    $terminationErrors = @()

    # Step 1: Revalidate Root Process Identity before termination (Anti-PID reuse)
    $rootIsAlive = [bool](Get-Process -Id $RootIdentity.Id -ErrorAction SilentlyContinue)
    $rootActiveAndVerified = $false

    if ($rootIsAlive) {
        if (Test-IsProcessRunningWithSameIdentity -IdentityRecord $RootIdentity) {
            $rootActiveAndVerified = $true
        } else {
            Write-Warning "[$Context] PID REUSE DETECTED on Root PID $($RootIdentity.Id)! Live process does not match original migration runner identity. Skipping root termination to protect unrelated workload."
            $terminationErrors += "Root PID $($RootIdentity.Id) failed identity revalidation due to PID reuse."
        }
    } else {
        Write-Host "[$Context] Verified root process PID $($RootIdentity.Id) has already exited. Inspecting tracked descendants..." -ForegroundColor Yellow
    }

    # Step 2: Terminate verified root process if active and verified
    if ($rootActiveAndVerified) {
        Write-Host "[$Context] Terminating verified runner root PID $($RootIdentity.Id)..." -ForegroundColor Yellow
        $tkOut = & $TaskkillPath /PID $RootIdentity.Id /T /F 2>&1
        $tkExit = $LASTEXITCODE
        if ($tkExit -ne 0) {
            $terminationErrors += "Root PID $($RootIdentity.Id) taskkill failed (Exit Code: $tkExit): $tkOut"
        }
        Start-Sleep -Milliseconds 400
    }

    # Step 3: Terminate only verified, tracked descendants with anti-PID reuse revalidation
    if ($TrackedDescendants) {
        foreach ($targetPid in $TrackedDescendants.Keys) {
            $descRecord = $TrackedDescendants[$targetPid]
            $liveDesc = Get-Process -Id $targetPid -ErrorAction SilentlyContinue
            if (-not $liveDesc) {
                continue
            }

            # Anti-PID reuse check on descendant
            $descIdentity = @{
                Id = $descRecord.Id
                Name = $descRecord.Name
                StartTime = $descRecord.StartTime
                CreationDate = $descRecord.CreationDate
            }

            if (-not (Test-IsProcessRunningWithSameIdentity -IdentityRecord $descIdentity)) {
                Write-Warning "[$Context] [PID REUSE] PID $targetPid does not match tracked descendant identity ($($descRecord.Name)). Skipping termination to protect unrelated process."
                continue
            }

            Write-Host "[$Context] Terminating verified descendant PID $targetPid ($($descRecord.Name))..." -ForegroundColor Yellow
            $tkChildOut = & $TaskkillPath /PID $targetPid /T /F 2>&1
            $tkChildExit = $LASTEXITCODE
            if ($tkChildExit -ne 0) {
                $terminationErrors += "Verified descendant PID $targetPid taskkill failed (Exit Code: $tkChildExit): $tkChildOut"
            }
            Start-Sleep -Milliseconds 200
        }
    }

    # Step 4: Verify that all verified Railora processes have terminated
    $survivingVerifiedPids = @()
    if ($rootActiveAndVerified -and (Get-Process -Id $RootIdentity.Id -ErrorAction SilentlyContinue)) {
        if (Test-IsProcessRunningWithSameIdentity -IdentityRecord $RootIdentity) {
            $survivingVerifiedPids += $RootIdentity.Id
        }
    }
    if ($TrackedDescendants) {
        foreach ($targetPid in $TrackedDescendants.Keys) {
            $descRecord = $TrackedDescendants[$targetPid]
            $liveDesc = Get-Process -Id $targetPid -ErrorAction SilentlyContinue
            if ($liveDesc) {
                $descIdentity = @{ Id = $descRecord.Id; Name = $descRecord.Name; StartTime = $descRecord.StartTime; CreationDate = $descRecord.CreationDate }
                if (Test-IsProcessRunningWithSameIdentity -IdentityRecord $descIdentity) {
                    $survivingVerifiedPids += $targetPid
                }
            }
        }
    }

    if ($survivingVerifiedPids.Count -eq 0 -and $terminationErrors.Count -eq 0) {
        $descCount = if ($TrackedDescendants) { $TrackedDescendants.Count } else { 0 }
        Write-Host "[$Context] Verified process tree (Root PID $($RootIdentity.Id) and $descCount verified descendants) successfully terminated." -ForegroundColor Green
        return $true
    }

    # Step 5: Safe Failure & Leak Reporting (Zero Unverified PIDs, No Dangerous Multi-Kill Command)
    Write-Host "[CRITICAL $Context LEAK] Failed to confirm termination for verified runner tree $($RootIdentity.Id)!" -ForegroundColor Red
    foreach ($err in $terminationErrors) {
        $sanitizedErr = Format-RedactedText -Text $err -Secret $Secret
        Write-Host "  Error: $sanitizedErr" -ForegroundColor Red
    }
    Write-Host "WARNING: Verified processes belonging to tree $($RootIdentity.Id) may still be running." -ForegroundColor Red
    Write-Host "PostgreSQL server rollback CANNOT be assumed while client processes may still be active." -ForegroundColor Red

    if ($survivingVerifiedPids.Count -gt 0) {
        Write-Host "The following verified Railora processes could NOT be confirmed terminated:" -ForegroundColor Yellow
        foreach ($sp in $survivingVerifiedPids) {
            $info = if ($TrackedDescendants -and $TrackedDescendants.ContainsKey($sp)) { "$($TrackedDescendants[$sp].Name) (Parent PID: $($TrackedDescendants[$sp].ParentId))" } else { "Root PID $sp ($($RootIdentity.Name))" }
            Write-Host "  PID: $sp | Details: $info" -ForegroundColor Yellow
        }
        Write-Host "ACTION REQUIRED: Manual operator investigation required." -ForegroundColor Yellow
        Write-Host "Inspect the process command line in Task Manager or via Get-CimInstance Win32_Process to verify ownership before manual termination." -ForegroundColor Yellow
    } else {
        Write-Host "ACTION REQUIRED: Termination errors were encountered during process shutdown. Manually verify running processes in Task Manager before proceeding." -ForegroundColor Yellow
    }
    Write-Host "STRICT RULE: DO NOT execute 'Stop-Process -Name java -Force'. Doing so will terminate unrelated Java processes on this machine." -ForegroundColor Red
    return $false
}

# ---------------------------------------------------------------------------
# ENVIRONMENT SNAPSHOT & ISOLATION PREPARATION
# ---------------------------------------------------------------------------
# Explicit list of all environment variables managed, modified, or defaulted by this runner
$ExplicitManagedVars = @(
    'SPRING_PROFILES_ACTIVE',
    'DB_URL', 'DB_USERNAME', 'DB_PASSWORD',
    'PGHOST', 'PGPORT', 'PGDATABASE', 'PGUSER',
    'POSTGRES_DB', 'POSTGRES_USER', 'POSTGRES_PASSWORD',
    'SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME', 'SPRING_DATASOURCE_PASSWORD',
    'JWT_ACCESS_SECRET', 'JWT_REFRESH_SECRET',
    'REDIS_HOST', 'REDIS_PORT', 'REDIS_USERNAME', 'REDIS_PASSWORD', 'REDIS_SSL_ENABLED',
    'CORS_ALLOWED_ORIGINS', 'RAILWAY_BASE_FARE'
)

$AllTrackedVarNames = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
foreach ($var in $ExplicitManagedVars) {
    [void]$AllTrackedVarNames.Add($var)
}

# Dynamically discover any existing ambient caller variables matching PG*, POSTGRES_*, or SPRING_DATASOURCE_*
Get-ChildItem env: | Where-Object {
    $_.Name -match '^(PG|POSTGRES_|SPRING_DATASOURCE_)'
} | ForEach-Object {
    [void]$AllTrackedVarNames.Add($_.Name)
}

# Snapshot pre-execution environment state before any script mutation occurs
$OriginalEnvSnapshot = @{}
foreach ($var in $AllTrackedVarNames) {
    if (Test-Path "env:$var") {
        $OriginalEnvSnapshot[$var] = (Get-Item "env:$var").Value
    } else {
        $OriginalEnvSnapshot[$var] = $null
    }
}

try {
    Write-Host '=================================================================' -ForegroundColor Cyan
    Write-Host ' Railora — Neon PostgreSQL Controlled Migration Runner (Phase 17.4D.2)' -ForegroundColor Cyan
    Write-Host '=================================================================' -ForegroundColor Cyan

    # ---------------------------------------------------------------------------
    # GUARD 1: Canonical Workspace Verification
    # ---------------------------------------------------------------------------
    $ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
    $RepoRoot = Split-Path -Parent $ScriptDir
    $PomPath = Join-Path $RepoRoot 'pom.xml'

    if (-not (Test-Path $PomPath)) {
        Write-Error "CRITICAL SAFETY ABORT: pom.xml not found at expected repository root: '$RepoRoot'."
        exit 1
    }

    $PomContent = Get-Content -Path $PomPath -Raw
    if ($PomContent -notmatch '<artifactId>railway_booking</artifactId>') {
        Write-Error "CRITICAL SAFETY ABORT: Workspace at '$RepoRoot' is not the canonical Railora project."
        exit 1
    }

    $MvnwPath = Join-Path $RepoRoot 'mvnw.cmd'
    if (-not (Test-Path $MvnwPath)) {
        Write-Error "CRITICAL SAFETY ABORT: Windows Maven wrapper 'mvnw.cmd' was not found at '$RepoRoot'."
        exit 1
    }

    Write-Host "[GUARD 1 PASS] Canonical Railora repository verified at '$RepoRoot'." -ForegroundColor Green

    # ---------------------------------------------------------------------------
    # GUARD 2: Database Connection Parameter Presence
    # ---------------------------------------------------------------------------
    if ([string]::IsNullOrWhiteSpace($DbUrl)) {
        Write-Error 'CRITICAL SAFETY ABORT: DB_URL is missing. Supply via parameter -DbUrl or $env:DB_URL.'
        exit 1
    }

    if ([string]::IsNullOrWhiteSpace($DbUsername)) {
        Write-Error 'CRITICAL SAFETY ABORT: DB_USERNAME is missing. Supply via parameter -DbUsername or $env:DB_USERNAME.'
        exit 1
    }

    if ([string]::IsNullOrWhiteSpace($DbPassword)) {
        Write-Error 'CRITICAL SAFETY ABORT: DB_PASSWORD is missing. Supply via parameter -DbPassword or $env:DB_PASSWORD.'
        exit 1
    }

    Write-Host '[GUARD 2 PASS] Database connection parameters are present.' -ForegroundColor Green

    # ---------------------------------------------------------------------------
    # GUARD 3: Structural JDBC URL & Target Endpoint Verification
    # ---------------------------------------------------------------------------
    if ($DbUrl -notmatch '^jdbc:postgresql://') {
        Write-Error "CRITICAL SAFETY ABORT: DB_URL must begin with 'jdbc:postgresql://'."
        exit 1
    }

    # Structural regex: jdbc:postgresql://host[:port]/database[?query]
    if ($DbUrl -match '^jdbc:postgresql://([^/?:]+)(?::(\d+))?/([^?]+)(?:\?(.*))?$') {
        $HostName     = $matches[1]
        $PortNumber   = $matches[2]
        $DatabaseName = $matches[3]
        $QueryString  = $matches[4]
    } else {
        Write-Error "CRITICAL SAFETY ABORT: Failed to parse structural components from DB_URL: '$DbUrl'."
        exit 1
    }

    # 3A. Reject local or test database targets
    if ($HostName -match '(localhost|127\.0\.0\.1|::1|postgres|local)') {
        Write-Error "CRITICAL SAFETY ABORT: Host '$HostName' points to a local or test environment. Target must be Neon."
        exit 1
    }

    # 3B. Reject PgBouncer pooler endpoints (Direct connection required for DDL/migrations)
    if ($HostName -match '-pooler') {
        Write-Error "CRITICAL SAFETY ABORT: Host '$HostName' is a Neon PgBouncer connection pooler (-pooler detected). Direct connection is strictly required for Flyway migrations."
        exit 1
    }

    # 3C. Enforce authorized AWS Singapore Neon region
    if ($HostName -notmatch '^[a-z0-9-]+(?:\.c-\d+)?\.ap-southeast-1\.aws\.neon\.tech$') {
        Write-Error "CRITICAL SAFETY ABORT: Host '$HostName' does not match the authorized AWS Singapore Neon region '*.ap-southeast-1.aws.neon.tech'."
        exit 1
    }

    # 3D. Enforce standard PostgreSQL port 5432 (reject PgBouncer port 6543)
    if (-not [string]::IsNullOrEmpty($PortNumber) -and $PortNumber -ne '5432') {
        Write-Error "CRITICAL SAFETY ABORT: Port '$PortNumber' detected. Direct Neon connections must use port 5432 (port 6543 pooler is prohibited)."
        exit 1
    }

    # 3E. Enforce target database name
    if ($DatabaseName -ne 'railway_booking_prod') {
        Write-Error "CRITICAL SAFETY ABORT: Target database is '$DatabaseName'. Must be strictly 'railway_booking_prod'."
        exit 1
    }

    # 3F. Structural Query Parameter & SSL Validation
    if ([string]::IsNullOrWhiteSpace($QueryString)) {
        Write-Error "CRITICAL SAFETY ABORT: DB_URL query string is missing. Parameter 'sslmode=require' is mandatory."
        exit 1
    }

    $QueryParamPairs = $QueryString.Split('&')
    $SslModeCount = 0
    $SslModeValue = $null

    foreach ($pair in $QueryParamPairs) {
        $kv = $pair.Split('=', 2)
        $paramKey = $kv[0].Trim().ToLowerInvariant()
        $paramVal = if ($kv.Length -gt 1) { $kv[1].Trim().ToLowerInvariant() } else { '' }

        if ($paramKey -eq 'sslmode') {
            $SslModeCount++
            $SslModeValue = $paramVal
        }

        if ($paramKey -eq 'ssl' -and $paramVal -eq 'false') {
            Write-Error "CRITICAL SAFETY ABORT: Insecure parameter 'ssl=false' detected in DB_URL."
            exit 1
        }
    }

    if ($SslModeCount -ne 1) {
        Write-Error "CRITICAL SAFETY ABORT: Exactly one 'sslmode' parameter is required. Found $SslModeCount occurrences."
        exit 1
    }

    if ($SslModeValue -ne 'require') {
        Write-Error "CRITICAL SAFETY ABORT: Parameter 'sslmode' must be 'require' (found: '$SslModeValue'). Conflicting or insecure modes are prohibited."
        exit 1
    }

    Write-Host "[GUARD 3 PASS] Structural URL verified: direct host '$HostName', port 5432, db '$DatabaseName', sslmode=require." -ForegroundColor Green

    # ---------------------------------------------------------------------------
    # GUARD 4: Neon Role Verification
    # ---------------------------------------------------------------------------
    if ($DbUsername -ne 'neondb_owner') {
        Write-Error "CRITICAL SAFETY ABORT: DB_USERNAME is '$DbUsername'. Expected authorized role 'neondb_owner'."
        exit 1
    }

    Write-Host "[GUARD 4 PASS] Database role verified as 'neondb_owner'." -ForegroundColor Green

    # ---------------------------------------------------------------------------
    # GUARD 5: Environment Isolation & Ambient Variable Sanitization
    # ---------------------------------------------------------------------------
    # Enforce production profile
    $env:SPRING_PROFILES_ACTIVE = 'production'

    # Clear all ambient PG*, POSTGRES_*, and SPRING_DATASOURCE_* variables for execution isolation
    Get-ChildItem env: | Where-Object {
        $_.Name -match '^(PG|POSTGRES_|SPRING_DATASOURCE_)'
    } | ForEach-Object {
        Remove-Item "env:$($_.Name)"
    }

    # Bind validated target credentials in session environment
    # (Resolved by application-production.properties: spring.datasource.url=${DB_URL}, etc.)
    $env:DB_URL      = $DbUrl
    $env:DB_USERNAME = $DbUsername
    $env:DB_PASSWORD = $DbPassword

    # Supply safe non-DB placeholders if not set by operator
    # (Allows migration-only mode to pass property binding without invoking external services)
    if ([string]::IsNullOrWhiteSpace($env:JWT_ACCESS_SECRET)) {
        $env:JWT_ACCESS_SECRET = '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'
    }
    if ([string]::IsNullOrWhiteSpace($env:JWT_REFRESH_SECRET)) {
        $env:JWT_REFRESH_SECRET = 'fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210'
    }
    # Use loopback IP to avoid DNS resolution delay; cache is explicitly disabled via command line
    if ([string]::IsNullOrWhiteSpace($env:REDIS_HOST)) {
        $env:REDIS_HOST = '127.0.0.1'
    }
    if ([string]::IsNullOrWhiteSpace($env:REDIS_PORT)) {
        $env:REDIS_PORT = '6379'
    }
    if ([string]::IsNullOrWhiteSpace($env:REDIS_USERNAME)) {
        $env:REDIS_USERNAME = 'default'
    }
    if ([string]::IsNullOrWhiteSpace($env:REDIS_PASSWORD)) {
        $env:REDIS_PASSWORD = 'unused-migration-placeholder-pwd'
    }
    if ([string]::IsNullOrWhiteSpace($env:REDIS_SSL_ENABLED)) {
        $env:REDIS_SSL_ENABLED = 'false'
    }
    if ([string]::IsNullOrWhiteSpace($env:CORS_ALLOWED_ORIGINS)) {
        $env:CORS_ALLOWED_ORIGINS = 'https://railora.vercel.app'
    }
    if ([string]::IsNullOrWhiteSpace($env:RAILWAY_BASE_FARE)) {
        $env:RAILWAY_BASE_FARE = '500.00'
    }

    Write-Host '[GUARD 5 PASS] Environment sanitized: ambient PG* and SPRING_DATASOURCE_* purged; credentials bound via $env:DB_*; loopback Redis placeholder set.' -ForegroundColor Green

    # ---------------------------------------------------------------------------
    # PRE-EXECUTION CONFIRMATION DISPLAY (NEVER PRINTS CREDENTIALS)
    # ---------------------------------------------------------------------------
    Write-Host ''
    Write-Host '-----------------------------------------------------------------' -ForegroundColor Yellow
    Write-Host ' PRE-EXECUTION TARGET BASELINE VERIFICATION' -ForegroundColor Yellow
    Write-Host '-----------------------------------------------------------------' -ForegroundColor Yellow
    Write-Host " Target Cloud Host : $HostName" -ForegroundColor White
    Write-Host " Target Database   : $DatabaseName" -ForegroundColor White
    Write-Host " Target Role       : $DbUsername" -ForegroundColor White
    Write-Host ' Port              : 5432 (Direct JDBC)' -ForegroundColor White
    Write-Host ' SSL Encryption    : sslmode=require (Verified, single parameter)' -ForegroundColor White
    Write-Host ' Password Status   : [PRESENT - REDACTED]' -ForegroundColor White
    Write-Host ' Spring Profile    : production' -ForegroundColor White
    Write-Host ' Web Application   : DISABLED (spring.main.web-application-type=none)' -ForegroundColor White
    Write-Host ' Background Reaper : DISABLED (railway.seat-hold-reaper.enabled=false)' -ForegroundColor White
    Write-Host ' Redis Cache Client: DISABLED (railway.cache.enabled=false)' -ForegroundColor White
    Write-Host ' Kafka Publisher   : DISABLED (railway.outbox.kafka.enabled=false)' -ForegroundColor White
    Write-Host ' Data Seeders      : DISABLED (admin=false, journey=false)' -ForegroundColor White
    Write-Host ' Migration Exit    : ENABLED (railway.migration.exit-after-startup=true)' -ForegroundColor White
    Write-Host " Execution Timeout : $TimeoutSeconds seconds" -ForegroundColor White
    Write-Host ' Flyway Migrations : V1 through V7 (Classpath db/migration)' -ForegroundColor White
    Write-Host ' Hibernate Action  : ddl-auto=validate (Verification only; no DDL)' -ForegroundColor White
    Write-Host '-----------------------------------------------------------------' -ForegroundColor Yellow
    Write-Host ''

    if (-not $Force) {
        $Confirmation = Read-Host "Type 'CONFIRM' to execute Flyway migrations against Neon PostgreSQL"
        if ($Confirmation -ne 'CONFIRM') {
            Write-Warning 'Migration cancelled by user. Zero operations executed.'
            exit 0
        }
    }

    # ---------------------------------------------------------------------------
    # CONTROLLED COMMAND CONSTRUCTION & EXECUTION
    # ---------------------------------------------------------------------------
    Write-Host "`nLaunching Spring Boot Migration-Only Execution..." -ForegroundColor Cyan

    # Application flags: DB credentials are intentionally excluded from CLI arguments
    # to eliminate shell command-splitting on '&' query parameters.
    $AppArgumentsList = @(
        '--spring.main.web-application-type=none',
        '--railway.seat-hold-reaper.enabled=false',
        '--railway.cache.enabled=false',
        '--railway.outbox.kafka.enabled=false',
        '--railway.data-seed.admin.enabled=false',
        '--railway.data-seed.journey.enabled=false',
        '--railway.migration.exit-after-startup=true'
    )
    $AppArgumentsJoined = $AppArgumentsList -join ' '

    # Correctly quote the property value for Windows ProcessStartInfo
    $MavenProperty = "-Dspring-boot.run.arguments=`"$AppArgumentsJoined`""

    Push-Location $RepoRoot
    try {
        $ProcessStartInfo = New-Object System.Diagnostics.ProcessStartInfo
        $ProcessStartInfo.FileName = 'cmd.exe'
        $ProcessStartInfo.Arguments = "/c `"`"$MvnwPath`" spring-boot:run $MavenProperty`""
        $ProcessStartInfo.UseShellExecute = $false
        $ProcessStartInfo.RedirectStandardOutput = $true
        $ProcessStartInfo.RedirectStandardError = $true
        $ProcessStartInfo.CreateNoWindow = $true

        $Process = New-Object System.Diagnostics.Process
        $Process.StartInfo = $ProcessStartInfo

        $OutputBuilder = New-Object System.Text.StringBuilder
        $ErrorBuilder  = New-Object System.Text.StringBuilder

        $OutHandler = {
            if (-not [string]::IsNullOrEmpty($EventArgs.Data)) {
                $sanitized = Format-RedactedText -Text $EventArgs.Data -Secret $DbPassword
                Write-Host $sanitized
                $null = $OutputBuilder.AppendLine($sanitized)
            }
        }
        $ErrHandler = {
            if (-not [string]::IsNullOrEmpty($EventArgs.Data)) {
                $sanitized = Format-RedactedText -Text $EventArgs.Data -Secret $DbPassword
                Write-Host $sanitized -ForegroundColor Red
                $null = $ErrorBuilder.AppendLine($sanitized)
            }
        }

        Register-ObjectEvent -InputObject $Process -EventName OutputDataReceived -Action $OutHandler | Out-Null
        Register-ObjectEvent -InputObject $Process -EventName ErrorDataReceived -Action $ErrHandler | Out-Null

        $LaunchTimeUtc = [System.DateTime]::UtcNow
        $Process.Start() | Out-Null
        $Process.BeginOutputReadLine()
        $Process.BeginErrorReadLine()

        # Immediate root process ownership capture & strict validation
        $RootIdentity = Assert-VerifiedMigrationRoot -ProcessId $Process.Id -LaunchTimeUtc $LaunchTimeUtc -ExpectedRepoRoot $RepoRoot

        # Bounded lifecycle monitoring loop with dynamic descendant tracking
        $Stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
        $FlywaySuccessDetected    = $false
        $HibernateSuccessDetected = $false
        $TrackedDescendants       = [System.Collections.Generic.Dictionary[int, psobject]]::new()

        while (-not $Process.HasExited) {
            Start-Sleep -Milliseconds 500

            # Continuously sample and track all descendant PIDs through verified parent-child lineage
            Update-TrackedMigrationDescendants -RootIdentity $RootIdentity -TrackedDescendants $TrackedDescendants

            $currentOutput = $OutputBuilder.ToString()
            if (-not $FlywaySuccessDetected -and ($currentOutput -match 'Successfully applied 7 migrations' -or $currentOutput -match 'Current version of schema .* 7')) {
                $FlywaySuccessDetected = $true
                Write-Host '[MONITOR] Flyway migration completion detected.' -ForegroundColor Green
            }
            if ($FlywaySuccessDetected -and -not $HibernateSuccessDetected -and ($currentOutput -match "Initialized JPA EntityManagerFactory for persistence unit 'default'")) {
                $HibernateSuccessDetected = $true
                Write-Host '[MONITOR] Hibernate schema validation completion detected.' -ForegroundColor Green
            }

            if ($Stopwatch.Elapsed.TotalSeconds -ge $TimeoutSeconds) {
                Write-Host "`n[TIMEOUT ABORT] Process did not exit within $TimeoutSeconds seconds." -ForegroundColor Red
                if (-not $Process.HasExited) {
                    $termSuccess = Stop-MigrationProcessTree -RootIdentity $RootIdentity -Context 'TIMEOUT' -Secret $DbPassword -TrackedDescendants $TrackedDescendants
                    if ($termSuccess) {
                        Write-Host '[TIMEOUT NOTICE] Client process tree was terminated. Note: PostgreSQL server-side rollback may take additional time to complete.' -ForegroundColor Yellow
                        Write-Host 'Do NOT retry immediately. Verify migration session termination in pg_stat_activity before taking any action.' -ForegroundColor Yellow
                    }
                }
                Write-Error "TIMEOUT ABORT: Migration execution timed out after $TimeoutSeconds seconds. Client termination invoked. Server-side transaction rollback must be verified in Neon pg_stat_activity. Inspect Flyway schema history and database state before taking any action. Do NOT run 'flyway repair'."
                exit 1
            }
        }

        # Drain and wait for all asynchronous redirected output events to complete dispatch
        $Process.WaitForExit()

        $ExitCode = $Process.ExitCode
    } finally {
        # Ensure no lingering process tree remains under any abnormal interruption
        if ($Process -and -not $Process.HasExited) {
            if ($RootIdentity) {
                Write-Host "`n[CLEANUP] Terminating lingering process tree for PID $($RootIdentity.Id)..." -ForegroundColor Yellow
                [void](Stop-MigrationProcessTree -RootIdentity $RootIdentity -Context 'CLEANUP' -Secret $DbPassword -TrackedDescendants $TrackedDescendants)
            } else {
                Write-Host "`n[CLEANUP] Terminating unverified started process PID $($Process.Id)..." -ForegroundColor Yellow
                try { $Process.Kill() } catch {}
            }
        }
        Pop-Location
    }

    # ---------------------------------------------------------------------------
    # POST-EXECUTION STATUS EVALUATION
    # ---------------------------------------------------------------------------
    Write-Host ''
    Write-Host '=================================================================' -ForegroundColor Cyan
    Write-Host ' POST-EXECUTION STATUS EVALUATION' -ForegroundColor Cyan
    Write-Host '=================================================================' -ForegroundColor Cyan

    $FinalLog = $OutputBuilder.ToString()
    $FlywayVerified = ($FinalLog -match 'Successfully applied 7 migrations' -or $FinalLog -match 'Current version of schema .* 7')
    $HibernateVerified = ($FinalLog -match "Initialized JPA EntityManagerFactory for persistence unit 'default'")

    if ($ExitCode -eq 0 -and $FlywayVerified -and $HibernateVerified) {
        Write-Host "`n[SUCCESS] Flyway migrations V1 through V7 successfully applied to Neon PostgreSQL!" -ForegroundColor Green
        Write-Host '[SUCCESS] Hibernate ddl-auto=validate completed successfully with zero schema mismatches.' -ForegroundColor Green
        Write-Host '[SUCCESS] Process exited cleanly with exit code 0.' -ForegroundColor Green
        Write-Host "`nNext Step: Run post-migration verification queries as outlined in docs/PHASE_17_4D_2_EXECUTION_RUNBOOK.md." -ForegroundColor Cyan
        exit 0
    } else {
        Write-Host "`n[FAILURE] Migration execution failed or terminated prematurely." -ForegroundColor Red
        Write-Host "  Exit Code            : $ExitCode (Expected: 0)" -ForegroundColor Red
        Write-Host "  Flyway Completed     : $FlywayVerified" -ForegroundColor Red
        Write-Host "  Hibernate Validated  : $HibernateVerified" -ForegroundColor Red
        Write-Host "`nPostgreSQL transactional DDL guarantees that any failed script rolls back once the connection disconnects and rollback completes on the server." -ForegroundColor Yellow
        Write-Host 'Do NOT retry immediately. Verify backend session termination in pg_stat_activity before taking further action.' -ForegroundColor Yellow
        Write-Host 'Inspect error output above to identify the root cause.' -ForegroundColor Yellow
        Write-Host "Do NOT run 'flyway repair' blindly. Refer to failure recovery protocol in docs/PHASE_17_4D_2_EXECUTION_RUNBOOK.md." -ForegroundColor Yellow
        exit 1
    }
} finally {
    # ---------------------------------------------------------------------------
    # ENVIRONMENT RESTORATION
    # ---------------------------------------------------------------------------
    # Restores original caller environment state on all paths (success, failure,
    # timeout, or exception). Pre-existing variables are restored to their original
    # values; variables created or defaulted by this script are purged from session.
    # Note: If credentials were already exported in the caller's session prior to
    # script execution, their original values are preserved and restored.
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
