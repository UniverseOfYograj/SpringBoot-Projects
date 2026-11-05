# Railora — Phase 17.4D.2: Neon PostgreSQL Migration Execution Runbook (Remediated Final)

**Document:** Authoritative Operational Execution Runbook for Live Neon Database Migration  
**Phase:** 17.4D.2 — Controlled Migration Execution  
**Target Architecture:** Neon PostgreSQL 18.6 (Direct JDBC, Port 5432, Connection Pooling OFF, SSL Required)  
**Associated Automation Script:** [`scripts/phase-17-4d-2-neon-migration.ps1`](file:///D:/railway-github-check/Railway-Booking-System/scripts/phase-17-4d-2-neon-migration.ps1)  
**Canonical Repository:** `D:\railway-github-check\Railway-Booking-System`  
**Git Root:** `D:\railway-github-check` (Branch: `main`)  

---

## 1. Operational Context & Certified Target Specifications

This runbook defines the authoritative, step-by-step procedure for applying Railora's schema migrations (V1 through V7) to the production Neon PostgreSQL database.

| Parameter | Specification | Live Preflight Baseline |
| :--- | :--- | :--- |
| **Neon Project** | `railora-production` | Neon Console |
| **Neon Branch** | `production` | Neon Console |
| **Database Name** | `railway_booking_prod` | `SELECT current_database();` -> `railway_booking_prod` |
| **Active Role** | `neondb_owner` | `SELECT current_user;` -> `neondb_owner` |
| **PostgreSQL Version** | `18.6` | `SELECT version();` -> PostgreSQL 18.6 |
| **Region** | AWS Singapore (`ap-southeast-1.aws.neon.tech`) | Cloud Topology Specification |
| **Connection Type** | Direct JDBC (Port 5432, `sslmode=require`) | Direct endpoint (PgBouncer `-pooler` port 6543 strictly prohibited) |
| **Initial DB State** | 0 tables in `public` schema | `SELECT count(*) FROM information_schema.tables WHERE table_schema='public';` -> `0` |
| **Initial History Table** | Absent | `to_regclass('public.flyway_schema_history')` -> `NULL` |
| **Extension Required** | `btree_gist` (v1.8 available, trusted) | Verified in Phase 17.4D.1 live query |

---

## 2. Architecture of the Certified Migration-Only Mode

Rather than launching the entire production web application (which binds port 8080, registers HTTP listeners, and starts background threads), Railora executes the migration in an **isolated, one-time Migration-Only Mode**:

```
+---------------------------------------------------------------------------------+
|            PowerShell Runner: scripts/phase-17-4d-2-neon-migration.ps1          |
+---------------------------------------------------------------------------------+
                                      |
        +------------------------------+------------------------------+
        |                                                             |
        v                                                             v
[Certified Safety Guards]                                   [Isolated Spring Boot CLI]
 - Environment snapshot (pre-execution state)                - Profile: production
 - Verify canonical repo & mvnw.cmd                          - Web server: NONE (no Tomcat)
 - Verify DB_URL, user, password                             - Schedulers: DISABLED
 - Structural URL validation:                                - Redis cache: DISABLED (in-memory)
   * Direct host (*.ap-southeast-1.aws.neon.tech)            - Kafka publisher: DISABLED
   * Reject -pooler (PgBouncer)                              - Data seeders: DISABLED
   * Port 5432 enforced (reject 6543)                        - Clean CLI arguments (zero raw URLs
   * Strictly 1 sslmode=require (no conflicting SSL)           or secrets on command line)
   * Database: railway_booking_prod                          - Spring Boot maps ${DB_URL},
 - Role: neondb_owner                                          ${DB_USERNAME}, ${DB_PASSWORD}
 - Purge ambient PG* & SPRING_DATASOURCE_*                            |
 - Bound credentials via $env:DB_*                                    v
 - Redact password in real-time streams             +---------------------------------+
 - Require explicit user 'CONFIRM'                  | Flyway Bean (initMethod=migrate)|
                                                    |  Applies V1 -> V2 -> ... -> V7  |
                                                    +---------------------------------+
                                                                      |
                                                                      v
                                                    +---------------------------------+
                                                    | Hibernate Schema Validation     |
                                                    |  (ddl-auto=validate)            |
                                                    +---------------------------------+
                                                                      |
                                                                      v
                                                    +---------------------------------+
                                                    | Bounded Monitor ($Timeout=180s) |
                                                    | Targeted process-tree kill      |
                                                    | (taskkill /PID $PID /T /F)      |
                                                    | Handles root + orphan children  |
                                                    | Zero global Java killing        |
                                                    +---------------------------------+
                                                                      |
                                                                      v
                                                    +---------------------------------+
                                                    | Outer Finally Block:            |
                                                    | Full Environment Restoration    |
                                                    | (Restores ambient vars; purges  |
                                                    | runner temporary defaults)      |
                                                    +---------------------------------+
```

### Certified Operational Highlights
1. **Zero Shell Command-Splitting on `&` & Multi-Quote Preservation:** JDBC URLs with query parameters (e.g. `sslmode=require`) are **never placed on the CLI command line**. Instead, the runner injects them into `$env:DB_URL`, and [`application-production.properties` line 1](file:///D:/railway-github-check/Railway-Booking-System/src/main/resources/application-production.properties#L1) maps `spring.datasource.url=${DB_URL}`. This completely eliminates Windows `cmd.exe` command-splitting on `&`. Furthermore, the ProcessStartInfo command line wraps the entire `/c` payload in outer quotes (`/c ""`...""`) to ensure Windows command processor preserves all internal quotes without stripping.
2. **Precise Process-Tree Termination & Orphan Handling:** If a timeout or abnormal termination occurs, the runner invokes `taskkill.exe /PID $Process.Id /T /F` targeting strictly its own root process tree (`cmd.exe` -> `mvnw.cmd` -> `java.exe`). The runner recursively enumerates child processes via WMI/CIM by parent process ID (`ParentProcessId = $Process.Id`), ensuring that only descendant processes of the runner are targeted. If the root process has already exited, it terminates any remaining orphan child processes directly by verified PID. It **never executes global termination** (such as `Stop-Process -Name java`), guaranteeing that unrelated Java applications on the host are completely protected.
3. **Environment Isolation & Guaranteed Restoration:** At startup, the runner snapshots all ambient environment variables matching `PG*`, `POSTGRES_*`, and `SPRING_DATASOURCE_*`, as well as all script-managed variables. In an outer `finally` block, it restores pre-existing variables to their exact original values and deletes any temporary default variables created by the runner. If credentials were already exported in the caller's session before running the script, their values are restored (not wiped).
4. **Loopback Redis Placeholder:** Uses `127.0.0.1` instead of an unresolvable domain to prevent operating system DNS resolution delays, while `--railway.cache.enabled=false` ensures zero connection attempts are made.
5. **Real-Time Password Redaction:** All stdout, stderr, and taskkill output streams pass through a regex redaction filter to eliminate accidental credential leakage in terminal logs.
6. **Dual-Gate Success Verification:** Requires BOTH Flyway migration completion AND Hibernate schema validation (`Initialized JPA EntityManagerFactory`), followed strictly by clean process exit code 0.
7. **Conservative Live Migration Session Inspection:** Recognizes that client process termination drops the client socket, but cloud PostgreSQL on Neon requires time to abort in-flight transactions and release locks. Instructs operators to conservatively inspect `pg_stat_activity` and `pg_locks` without relying on overly restrictive filters, clearly distinguishing confirmed migration sessions from ambiguous and unrelated sessions.

---

## 3. Automated Safety Guards in `phase-17-4d-2-neon-migration.ps1`

The script enforces comprehensive safety gates before executing any command:

1. **Guard 1 (Canonical Workspace):** Confirms `pom.xml` contains `<artifactId>railway_booking</artifactId>` and `mvnw.cmd` exists at the repository root.
2. **Guard 2 (Parameter Presence):** Confirms `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` are non-empty.
3. **Guard 3 (Structural URL Verification):**
   - Protocol must be `jdbc:postgresql://`.
   - Host must match `^[a-z0-9-]+(?:\.c-\d+)?\.ap-southeast-1\.aws\.neon\.tech$`.
   - Rejects any host containing `-pooler`.
   - Rejects `localhost`, `127.0.0.1`, `::1`, or `local`.
   - Port must be `5432` if specified (port `6543` pooler strictly rejected).
   - Database name must be exactly `railway_booking_prod`.
   - Query string must contain exactly one `sslmode` parameter with value `require`.
   - Rejects conflicting or insecure parameters (`sslmode=disable`, `ssl=false`, etc.).
4. **Guard 4 (Role Verification):** Requires database username to equal `neondb_owner`.
5. **Guard 5 (Environment Isolation & Ambient Variable Sanitization):**
   - Enforces `SPRING_PROFILES_ACTIVE=production`.
   - Purges all ambient `PG*`, `POSTGRES_*`, and `SPRING_DATASOURCE_*` variables to prevent local configuration bleed.
   - Binds target credentials into `$env:DB_URL`, `$env:DB_USERNAME`, `$env:DB_PASSWORD`.
   - Sets safe placeholder defaults for non-database mandatory properties (`JWT_ACCESS_SECRET`, `JWT_REFRESH_SECRET`, `REDIS_HOST=127.0.0.1`, etc.).
6. **Guard 6 (Environment Restoration Invariant):**
   - An outer `finally` block guarantees that the caller's session environment is restored to its exact pre-execution state on all exit paths (success, failure, timeout, or terminating exception).

---

## 4. Step-by-Step Operator Execution Procedure

### Step 1: Open Windows PowerShell in the Canonical Repository
```powershell
cd "D:\railway-github-check\Railway-Booking-System"
```

### Step 2: Session Environment & Credential Management
Set target credentials in memory for the migration execution:

```powershell
$env:DB_URL      = "jdbc:postgresql://<neon-endpoint>.ap-southeast-1.aws.neon.tech/railway_booking_prod?sslmode=require"
$env:DB_USERNAME = "neondb_owner"
$env:DB_PASSWORD = "<your-neon-password>"
```

> [!IMPORTANT]
> **Understanding Shell Environment Scoping vs. Child Process Cleanup**  
> - **Parent Shell vs. Child Process:** In Windows PowerShell, environment variables exported in the current console (like `$env:DB_PASSWORD`) live in the parent console's process memory. If you launch a child PowerShell process (`powershell.exe -File ...`), that child process inherits a copy of the parent's environment. When the child process exits, its own process memory is reclaimed, but the credentials you exported in the parent console **remain present in that parent console**.
> - **Safe Parent-Shell Cleanup:** After completing or aborting the migration, if you set credentials in your persistent console, remove them explicitly:
>   ```powershell
>   Remove-Item env:DB_URL, env:DB_USERNAME, env:DB_PASSWORD -ErrorAction SilentlyContinue
>   ```
>   *(Note: If `DB_URL` or `DB_USERNAME` were pre-existing variables configured in your personal shell profile before migration work, restore their prior values rather than deleting them).*
> - **Best Practice (Disposable Migration Shell):** To guarantee complete isolation, open a dedicated PowerShell console window solely for the migration, export credentials and run the script there, and then close the window (`exit`). Closing the terminal destroys all session memory and credentials immediately.

### Step 3: Run the Remediated Migration Runner
```powershell
.\scripts\phase-17-4d-2-neon-migration.ps1
```

*(Optional parameters: `-TimeoutSeconds 240` to adjust timeout; `-Force` to bypass interactive confirmation)*.

### Step 4: Review Pre-Execution Confirmation
The script displays the target configuration and pauses for confirmation:
```text
-----------------------------------------------------------------
 PRE-EXECUTION TARGET BASELINE VERIFICATION
-----------------------------------------------------------------
 Target Cloud Host : ep-...ap-southeast-1.aws.neon.tech
 Target Database   : railway_booking_prod
 Target Role       : neondb_owner
 Port              : 5432 (Direct JDBC)
 SSL Encryption    : sslmode=require (Verified, single parameter)
 Password Status   : [PRESENT - REDACTED]
 Spring Profile    : production
 Web Application   : DISABLED (spring.main.web-application-type=none)
 Background Reaper : DISABLED (railway.seat-hold-reaper.enabled=false)
 Redis Cache Client: DISABLED (railway.cache.enabled=false)
 Kafka Publisher   : DISABLED (railway.outbox.kafka.enabled=false)
 Data Seeders      : DISABLED (admin=false, journey=false)
 Migration Exit    : ENABLED (railway.migration.exit-after-startup=true)
 Execution Timeout : 180 seconds
 Flyway Migrations : V1 through V7 (Classpath db/migration)
 Hibernate Action  : ddl-auto=validate (Verification only; no DDL)
-----------------------------------------------------------------

Type 'CONFIRM' to execute Flyway migrations against Neon PostgreSQL:
```
Type `CONFIRM` and press Enter.

### Step 5: Observe Live Execution Logs
Verify the expected output signatures:
```text
INFO  [main] o.f.c.i.database.base.Database          : Database: jdbc:postgresql://... (PostgreSQL 18.6)
INFO  [main] o.f.core.internal.command.DbValidate    : Successfully validated 7 migrations
INFO  [main] o.f.c.i.s.DefaultSqlScriptExecutor      : DB: Creating schema "public" ...
INFO  [main] o.f.core.internal.command.DbMigrate     : Current version of schema "public": << Empty Schema >>
INFO  [main] o.f.core.internal.command.DbMigrate     : Migrating schema "public" to version "1 - initial schema"
INFO  [main] o.f.core.internal.command.DbMigrate     : Migrating schema "public" to version "2 - add segment range and gist exclusion"
INFO  [main] o.f.core.internal.command.DbMigrate     : Migrating schema "public" to version "3 - add tatkal admission to seat allocations"
INFO  [main] o.f.core.internal.command.DbMigrate     : Migrating schema "public" to version "4 - add pnr to bookings"
INFO  [main] o.f.core.internal.command.DbMigrate     : Migrating schema "public" to version "5 - create outbox events"
INFO  [main] o.f.core.internal.command.DbMigrate     : Migrating schema "public" to version "6 - add outbox claims and retry schedule"
INFO  [main] o.f.core.internal.command.DbMigrate     : Migrating schema "public" to version "7 - add razorpay payment references"
INFO  [main] o.f.core.internal.command.DbMigrate     : Successfully applied 7 migrations to schema "public"
...
INFO  [main] org.hibernate.orm.dialect               : HHH000490: Using dialect: org.hibernate.dialect.PostgreSQLDialect
INFO  [main] o.s.o.j.LocalContainerEntityManagerFactoryBean : Initialized JPA EntityManagerFactory for persistence unit 'default'
INFO  [main] i.y.l.r.m.MigrationExitRunner           : [MIGRATION-EXIT] Spring Boot migration-only startup completed successfully. Initiating clean context shutdown.
...
[MONITOR] Flyway migration completion detected.
[MONITOR] Hibernate schema validation completion detected.

[SUCCESS] Flyway migrations V1 through V7 successfully applied to Neon PostgreSQL!
[SUCCESS] Hibernate ddl-auto=validate completed successfully with zero schema mismatches.
[SUCCESS] Process exited cleanly with exit code 0.
```

---

## 5. Post-Migration Live Database Verification Queries

Execute these read-only SQL queries in Neon SQL Editor or `psql` to certify database state:

### 1. Verify Flyway Schema History (Must show 7 successful migrations)
```sql
SELECT installed_rank, version, description, type, script, execution_time, success
FROM public.flyway_schema_history
ORDER BY installed_rank ASC;
```
*Expected Result:* Exactly 7 rows, versions `1` through `7`, all with `success = true`.

### 2. Verify Public Schema Table Inventory (Must return 19 tables)
```sql
SELECT table_name 
FROM information_schema.tables 
WHERE table_schema = 'public' 
ORDER BY table_name;
```
*Expected Inventory (19 tables: 18 application + 1 Flyway history):*
`bookings`, `coaches`, `flyway_schema_history`, `journey_seat_inventory`, `journey_stops`, `journeys`, `outbox_events`, `payment_transactions`, `processed_consumer_events`, `razorpay_refunds`, `razorpay_webhook_events`, `refresh_tokens`, `seat_allocations`, `seats`, `stations`, `train_routes`, `train_stops`, `trains`, `users`.

### 3. Verify `btree_gist` Extension Installation
```sql
SELECT extname, extversion 
FROM pg_extension 
WHERE extname = 'btree_gist';
```
*Expected Result:* Exactly 1 row (`btree_gist`, version `1.8`).

### 4. Verify Authoritative GiST Exclusion Constraint
```sql
SELECT conname, contype, pg_get_constraintdef(oid) 
FROM pg_constraint 
WHERE conname = 'seat_alloc_no_overlap_excl';
```
*Expected Result:* `EXCLUDE USING gist (inventory_id WITH =, segment_range WITH &&) WHERE ((status = ANY (ARRAY['HOLD'::character varying, 'BOOKED'::character varying]::text[])))`.

### 5. Verify PNR Format Check Constraint & Unique Constraint
```sql
SELECT conname, contype, pg_get_constraintdef(oid)
FROM pg_constraint
WHERE conrelid = 'public.bookings'::regclass
  AND conname IN ('uk_bookings_pnr', 'chk_bookings_pnr_format');
```
*Expected Result:* `uk_bookings_pnr` (Unique) and `chk_bookings_pnr_format` (Check: `pnr ~ '^[0-9]{10}$'`).

---

## 6. Timeout, Process-Tree Failure & Non-Destructive Recovery Protocol

If the migration runner terminates with an error or times out:

### 6.1 Server-Side Rollback Mechanics & Asynchronous Disconnect
> [!IMPORTANT]
> **Client Process Termination Does NOT Prove Server Rollback Has Completed**  
> When `taskkill.exe /PID $Process.Id /T /F` terminates the client process tree, the client's TCP socket closes.  
> However, PostgreSQL on Neon must detect the closed socket, abort the in-flight statement, roll back the transaction, release advisory locks (specifically the Flyway migration lock), and release relation locks.  
> This server-side cleanup may take several seconds.  
> **DO NOT RETRY IMMEDIATELY.** Retrying while the old backend connection is still rolling back will lead to lock acquisition timeouts or connection contention.

- **Transactional DDL Invariant in PostgreSQL:**
  - PostgreSQL wraps each Flyway migration script in a separate transaction.
  - If Flyway commits V1–V4 and the process times out during V5:
    1. Scripts V1–V4 were committed in completed transactions; they remain recorded in `flyway_schema_history` with `success = true`.
    2. Script V5 was in-flight inside an active transaction. Once PostgreSQL completes transaction abort, it executes an **atomic rollback of V5**.
    3. Script V5 leaves **zero partial tables, columns, or constraints** in the database.
    4. Script V5 is **not** recorded in `flyway_schema_history`.

---

### 6.2 Step 1: Precise Client Process-Tree Verification & Anti-PID Reuse Inspection
If the runner timed out or encountered an abnormal interruption:
1. **Automated Root & Lineage Validation:**  
   The runner automatically validates that its root process is the exact `cmd.exe` process launched for this migration (validating executable name, launch timestamp, command-line invocation referencing `mvnw` and `spring-boot:run`, and rejecting system PIDs). During execution, all descendant processes (`cmd.exe`, `conhost.exe`, `mvnw.cmd`, `java.exe`) are dynamically tracked and their identities are re-validated against PID reuse before any termination is attempted.

2. **Manual Operator Process Verification:**  
   If manual verification is required:
   ```powershell
   # Inspect active processes strictly associated with the verified runner PID:
   # (Replace <RUNNER_ROOT_PID> with the PID emitted by the runner during startup/timeout)
   Get-CimInstance Win32_Process | Where-Object { 
       $_.ProcessId -eq <RUNNER_ROOT_PID> -or $_.ParentProcessId -eq <RUNNER_ROOT_PID> 
   } | Select-Object ProcessId, ParentProcessId, Name, CommandLine | Format-Table -AutoSize
   ```

   Before terminating any process manually:
   - Verify that its `Name` is `cmd.exe` or `java.exe`.
   - Verify that its `CommandLine` explicitly references `railway_booking` or the target repository.
   - Verify that its start time matches the migration execution window (guarding against PID reuse).
   - If ownership cannot be established with 100% certainty, **do not terminate**. Stop and investigate.

> [!CAUTION]
> **STRICT BAN ON GLOBAL JAVA PROCESS TERMINATION**  
> **DO NOT execute `Stop-Process -Name java -Force`.**  
> Indiscriminately killing all Java processes will terminate unrelated Java workloads on the machine (e.g. IDEs, background build daemons, developer tools).  
> If process ownership cannot be established by parent PID, manually inspect the command line of all running Java processes before taking any action:
> ```powershell
> Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Select-Object ProcessId, ParentProcessId, CommandLine | Format-List
> ```
> Verify that the `CommandLine` references `railway_booking` or the target repository before terminating any individual PID. If ownership cannot be established, halt and perform manual inspection.

---

### 6.3 Step 2: Conservative Live Neon Session & Lock Inspection (`pg_stat_activity` and `pg_locks`)
Before taking ANY further action, open the Neon SQL Editor or connect via `psql` and execute the following queries to inspect active database sessions and lingering locks:

#### Query 1: Comprehensive Target Database Session Inventory
Inspect all non-self connections on `railway_booking_prod`. Do not filter out sessions based on query text or transaction state:

```sql
SELECT pid,
       usename,
       application_name,
       client_addr,
       state,
       backend_start,
       xact_start,
       query_start,
       state_change,
       wait_event_type,
       wait_event,
       query
FROM pg_stat_activity
WHERE datname = 'railway_booking_prod'
  AND pid <> pg_backend_pid()
ORDER BY backend_start DESC;
```

#### Query 2: Comprehensive Relation & Advisory Lock Inspection
Check if ANY connection holds ANY lock on `public.*` relations or ANY session-level advisory lock (Flyway uses advisory locks that persist even in `state = 'idle'`):

```sql
SELECT l.pid,
       a.usename,
       a.application_name,
       a.client_addr,
       a.state,
       l.locktype,
       COALESCE(l.relation::regclass::text, l.locktype) AS locked_resource,
       l.mode,
       l.granted,
       a.query
FROM pg_locks l
JOIN pg_stat_activity a ON l.pid = a.pid
WHERE a.datname = 'railway_booking_prod'
  AND l.pid <> pg_backend_pid()
  AND (
    l.relation::regclass::text ILIKE 'public.%'
    OR l.locktype = 'advisory'
  )
ORDER BY l.pid;
```

#### Conservative Session Categorization Rules:
> [!IMPORTANT]
> **Cardinal Operational Rule:**  
> **If reliable ownership cannot be established, the safe outcome is to wait and investigate rather than terminate.**  
> Session ownership is never guaranteed by a shared public IP, database role, timestamp, or generic lock/query evidence alone.  
> **NEVER automatically terminate any database session.** Do not run `pg_terminate_backend()` based on heuristic categorization.

Categorize all observed sessions strictly into three mutually exclusive categories:

| Category | Identification Criteria (Corroboration Required) | Required Operator Action |
| :--- | :--- | :--- |
| **Category A: Confirmed Migration Session** | **Requires MULTIPLE corroborating indicators:**<br>1. Target database is `railway_booking_prod` and user is `neondb_owner`.<br>2. `backend_start` strictly matches the migration runner execution timeframe window.<br>3. `client_addr` matches the operator's public/NAT IP address.<br>4. Holds an active Flyway session-level advisory lock (Query 2) OR relation lock on `public.*` (e.g. `flyway_schema_history` or Railora tables), OR `query` references active Railora DDL / Flyway statements. | **DO NOT TERMINATE AUTOMATICALLY.**<br>Wait for PostgreSQL automatic server-side transaction rollback and connection teardown (typically 15–30 seconds after client process termination). When client TCP connection terminates, PostgreSQL aborts in-flight transactions and releases all advisory and relation locks automatically.<br>If the session remains in `pg_stat_activity` beyond 5 minutes, **do NOT terminate blindly**; stop and escalate to the Database Administrator or Neon Console to inspect socket state. |
| **Category B: Possible / Ambiguous Session** | **Inconclusive or partial indicators:**<br>1. Generic `application_name = 'PostgreSQL JDBC Driver'` without corroborating client IP or timestamp match.<br>2. Unrelated advisory locks or relations outside the Railora migration scope.<br>3. Missing query metadata, blank `application_name`, or generic last query (e.g. `COMMIT`, `SET ...`, `SELECT 1`).<br>4. Uncertain `backend_start` timestamp that does not precisely align with runner launch. | **DO NOT TERMINATE.**<br>Must be displayed for manual inspection; **NEVER** automatically classified as migration session; **NEVER** recommend `pg_terminate_backend()`.<br>An idle session holding no locks causes zero harm. Allow connection to time out or idle-disconnect naturally. Investigate origin safely. |
| **Category C: Unrelated Session** | **External or Cloud Management Sessions:**<br>1. Connected from Neon Cloud Console or Neon SQL Editor.<br>2. Neon background cloud monitoring / health checks (`application_name` contains console/cloud worker or query is `SELECT ... FROM pg_stat_activity`).<br>3. Internal administrative or replication workers. | **STRICTLY FORBIDDEN TO TERMINATE.**<br>Terminating cloud control-plane or monitoring sessions risks disrupting database health and administrative access. |

---

### 6.4 Step 3: Inspect Flyway Schema History & Schema Inventory
Once zero migration backend sessions and locks remain, inspect the exact committed state of the database:

1. **Check Flyway Schema History:**
   ```sql
   SELECT installed_rank, version, description, type, script, execution_time, success
   FROM public.flyway_schema_history
   ORDER BY installed_rank ASC;
   ```
   Determine the highest recorded version and verify that all rows report `success = true`.

2. **Check Table Inventory in Public Schema:**
   ```sql
   SELECT table_name
   FROM information_schema.tables
   WHERE table_schema = 'public'
   ORDER BY table_name;
   ```
   Cross-reference the table count with the completed migrations (e.g., if V1 completed, expect 11 tables + `flyway_schema_history` = 12 tables; if V4 completed, 13 tables; if V7 completed, 19 tables).

---

### 6.5 Step 4: Strict Ban on Blind `flyway repair`
> [!CAUTION]
> **DO NOT run `flyway repair` blindly.**  
> As proven by bytecode analysis in Phase 17.4D.1.1:  
> In PostgreSQL, failed or rolled-back transactional migrations do **not** write a row with `success = false`. The transaction rollback rolls back the metadata insertion into `flyway_schema_history`.  
> Therefore, there is NO failed row to repair. Running `flyway repair` when there is no failed row does nothing, or worse, if a checksum mismatch exists it could align checksums against corrupt state without proper diagnosis.  
> **DO NOT manually drop tables or delete rows from `flyway_schema_history`.**

---

### 6.6 Step 5: Safe Resolution & Controlled Re-Execution
Only after:
1. Local process inspection confirms zero orphaned migration child processes.
2. `pg_stat_activity` and `pg_locks` confirm zero lingering migration sessions or locks for `neondb_owner`.
3. `flyway_schema_history` and `information_schema.tables` confirm the schema state is clean and consistent.
4. The root cause of the timeout or failure (e.g. transient network latency, proxy timeout) has been identified and resolved.

May the operator initiate a re-run with an extended timeout threshold:
```powershell
.\scripts\phase-17-4d-2-neon-migration.ps1 -TimeoutSeconds 300
```
Flyway will automatically detect that previously committed migrations are already applied and resume smoothly from the next pending migration.

---

## 7. Baseline Safeguard: Post-Migration Neon Snapshot

Once verification queries confirm the schema is intact:
1. Open the [Neon Console](https://console.neon.tech).
2. Navigate to project `railora-production` > branch `production`.
3. Create a **Branch Snapshot** named `baseline-v1-v7-schema`.
4. This snapshot serves as the immutable schema baseline for Railora before application traffic is routed to the cluster.
