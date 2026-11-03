# ============================================================================
# Railora Railway Booking System: Safe Isolated Test Environment Provisioner
# File: scripts/setup_test_db.ps1
#
# SAFETY INVARIANTS:
# - Requires explicit parameter -ConfirmTargetDatabase "railway_booking_test".
# - Hard-aborts if the target matches 'railway_booking'.
# - Never embeds passwords or credentials.
# - Pure provisioning: does not drop existing data or execute application tests.
# ============================================================================

[CmdletBinding()]
param (
    [Parameter(Mandatory = $true, HelpMessage = "Type 'railway_booking_test' to confirm target database")]
    [string]$ConfirmTargetDatabase,

    [Parameter(Mandatory = $false)]
    [string]$PgHost = "localhost",

    [Parameter(Mandatory = $false)]
    [int]$PgPort = 5432,

    [Parameter(Mandatory = $false)]
    [string]$PgUser = "postgres"
)

# Safety Gate 1: Strict Confirmation Parameter Check
if ($ConfirmTargetDatabase -ne "railway_booking_test") {
    Write-Error "CRITICAL SAFETY ABORT: -ConfirmTargetDatabase must be exactly 'railway_booking_test' (received: '$ConfirmTargetDatabase')."
    exit 1
}

# Safety Gate 2: Explicit Rejection of Development Database
if ($ConfirmTargetDatabase -match "^railway_booking$") {
    Write-Error "CRITICAL SAFETY ABORT: Attempted execution against development database 'railway_booking'. Halting."
    exit 1
}

# Safety Gate 3: Check psql availability
$psqlCmd = Get-Command psql -ErrorAction SilentlyContinue
if (-not $psqlCmd) {
    Write-Error "PREREQUISITE MISSING: 'psql' command line client was not found in PATH. Install PostgreSQL tools or add them to PATH."
    exit 1
}

# Safety Gate 4: Script Path Verification
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$SqlScriptPath = Join-Path $ScriptDir "create_railway_booking_test.sql"
if (-not (Test-Path $SqlScriptPath)) {
    Write-Error "MISSING ASSET: SQL script '$SqlScriptPath' not found."
    exit 1
}

Write-Host "Safety Gates PASSED. Preparing database 'railway_booking_test' on ${PgHost}:${PgPort}..." -ForegroundColor Cyan

# Execute creation script against PostgreSQL default maintenance database 'postgres'
$psqlArgs = @(
    "-h", $PgHost,
    "-p", $PgPort,
    "-U", $PgUser,
    "-d", "postgres",
    "-v", "ON_ERROR_STOP=1",
    "-f", $SqlScriptPath
)

& psql.exe @psqlArgs
if ($LASTEXITCODE -ne 0) {
    Write-Error "EXECUTION FAILED: psql returned non-zero exit code ($LASTEXITCODE). Verify PostgreSQL server is running and user '$PgUser' has CREATEDB privilege."
    exit $LASTEXITCODE
}

# Safety Gate 5: Post-execution Verification of Target Database
Write-Host "Verifying 'railway_booking_test' and extensions..." -ForegroundColor Cyan
$verifyQuery = "SELECT current_database(), (SELECT count(*) FROM pg_extension WHERE extname = 'btree_gist') AS has_btree_gist;"
$verifyResult = & psql.exe -h $PgHost -p $PgPort -U $PgUser -d "railway_booking_test" -tAc $verifyQuery

if ($verifyResult -match "^railway_booking_test\|1\s*$") {
    Write-Host "VERIFICATION SUCCESSFUL: 'railway_booking_test' is active and btree_gist extension is enabled." -ForegroundColor Green
    Write-Host ""
    Write-Host "Next Steps (Run in separate terminal when ready):" -ForegroundColor Yellow
    Write-Host "1. Launch Spring Boot targeting test database (applies Flyway migrations V1-V5 and seeds journey):"
    Write-Host "   `$env:DB_URL = 'jdbc:postgresql://${PgHost}:${PgPort}/railway_booking_test'"
    Write-Host "   `$env:RAILWAY_OUTBOX_KAFKA_ENABLED = 'false'"
    Write-Host "   `$env:SEED_JOURNEY_DATA_ENABLED = 'true'"
    Write-Host "   mvn spring-boot:run -Dspring-boot.run.arguments=`"--spring.datasource.url=jdbc:postgresql://${PgHost}:${PgPort}/railway_booking_test --DB_URL=jdbc:postgresql://${PgHost}:${PgPort}/railway_booking_test --railway.outbox.kafka.enabled=false --railway.data-seed.journey.enabled=true`""
    Write-Host ""
    Write-Host "2. Register a smoke test user (via public endpoint):"
    Write-Host "   curl -X POST http://localhost:8080/api/auth/register -H 'Content-Type: application/json' -d '{`"username`":`"smoke_test_user`",`"fullName`":`"Smoke Test User`",`"email`":`"smoke_test_user@railwaybooking.local`",`"password`":`"SmokePassword123!`"}'"
    Write-Host ""
    Write-Host "3. Execute JMeter write-lifecycle smoke test:"
    Write-Host "   & '<jmeter-path>\bin\jmeter.bat' -n -t performance/smoke-write-lifecycle.jmx -l performance/results/smoke-write.jtl -Jhost=localhost -Jport=8080 -Jusername=smoke_test_user -Jpassword=SmokePassword123! -JjourneyId=1 -JseatId=1"
} else {
    Write-Error "POST-VERIFICATION FAILED: Verification query returned unexpected result: '$verifyResult'."
    exit 1
}
