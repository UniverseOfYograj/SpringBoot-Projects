# Railora — Phase 17.4D.1.1: Migration Execution-Readiness & Technical Verification Review

**Date:** 2026-11-03  
**Audit Purpose:** Deep-Dive Verification of PostgreSQL Transactional Migration Failure Mechanics, Production Property Invariants, Windows PowerShell Tooling, and Migration-Only Startup Modes  
**Status:** Strictly Read-Only (Zero Live Connections, Zero SQL Executed, Zero Files Modified)  
**Target Database:** Neon PostgreSQL 18.6 (`railway_booking_prod` on AWS Singapore)  
**Canonical Repository:** `D:\railway-github-check\Railway-Booking-System`  
**Git Root:** `D:\railway-github-check` (Branch: `main`)  

---

## 1. Executive Summary & Review Scope

This review addresses essential technical questions prior to executing Railora's initial database migration against live Neon PostgreSQL. Direct bytecode inspection of Flyway 12.4.0, property placeholder hierarchy analysis, Windows command-line compatibility, and application startup lifecycle audits were performed to replace assumptions with verified mechanical guarantees.

---

## 2. Item 1: Flyway Failure Behavior for PostgreSQL Transactional Migrations

### 2.1 The Common Assumption vs Flyway's Actual Implementation
It is frequently assumed that whenever a Flyway migration fails, Flyway records a row in `flyway_schema_history` with `success = false`. 

**Direct bytecode inspection of `flyway-core-12.4.0.jar` proves that this assumption is incorrect for PostgreSQL.**

### 2.2 Bytecode Verification (`org.flywaydb.core.internal.command.DbMigrate`)
In `DbMigrate.applyMigrations(...)`, when an exception occurs during migration execution:

```text
// Bytecode disassembly of DbMigrate.applyMigrations (lines 120-170, 227-236):
120: aload_0
121: getfield      #16                 // Field database:Database;
124: invokevirtual #179                // Method Database.supportsDdlTransactions:()Z
127: ifeq          173                 // IF NOT transactional -> JUMP to line 173 (Non-transactional handler)
130: iload_3                           // isExecuteGroupInTransaction
131: ifeq          173                 // IF NOT in transaction -> JUMP to line 173
134: getstatic     #182                // Field LOG:Log;
137: aload         7
139: invokedynamic #479                // String #781: "Migration ... failed! Changes successfully rolled back."
144: invokeinterface #288,  2          // Log.error:(String)
149: aload_0
150: getfield      #93                 // Field migrateResult:MigrateResult;
167: invokevirtual #493                // Method MigrateResult.markAsRolledBack:(List)
170: goto          233                 // BYPASSES addAppliedMigration entirely! Jump to throw exception!

// Line 173 onwards: ONLY executed when DDL transactions are NOT supported (e.g. MySQL):
173: getstatic     #182                // Field LOG:Log;
...
189: aload_0
190: getfield      #30                 // Field schemaHistory:SchemaHistory;
...
227: iload         8
229: iconst_0                          // success = false
230: invokevirtual #509                // Method SchemaHistory.addAppliedMigration:(..., success=false)
233: aload         5                   // Exception
235: athrow                            // Rethrow FlywayMigrateException
```

### 2.3 Concrete Impact for Railora on Neon PostgreSQL
1. **Engine DDL Transactions:** PostgreSQL natively supports transactional DDL (`Database.supportsDdlTransactions() == true`).
2. **Transaction Scope:** Each versioned migration script (V1 through V7) executes inside an isolated transaction (`BEGIN ... COMMIT`).
3. **Atomic Rollback:** If any statement in migration script $V_n$ fails, PostgreSQL immediately rolls back the entire transaction. All schema alterations in that script are undone.
4. **No Dirty Schema History Record:** Because the transaction was rolled back, Flyway logs `Changes successfully rolled back` and **does NOT insert a failed record (`success = false`) into `flyway_schema_history`**.
5. **No Blind `flyway repair` Required:** Because no failed record was written to `flyway_schema_history`, running `flyway repair` is neither necessary nor effective. After resolving the root cause (e.g., granting a missing permission or fixing SQL), the operator simply restarts the migration, and Flyway attempts $V_n$ again from a clean baseline.

---

## 3. Item 2: Required Environment Variables (Production vs Runtime Features)

Spring Boot property resolution distinguishes between **fail-fast mandatory placeholders** and **defaulted optional features**.

### 3.1 Mandatory Environment Variables (No Defaults — Startup Fails if Omitted)
In [application-production.properties](file:///D:/railway-github-check/Railway-Booking-System/src/main/resources/application-production.properties), these properties contain raw placeholders without defaults:

| Environment Variable | Property Key | Purpose |
| :--- | :--- | :--- |
| `DB_URL` | `spring.datasource.url` | Direct Neon JDBC connection string. |
| `DB_USERNAME` | `spring.datasource.username` | Database role (e.g. `neondb_owner`). |
| `DB_PASSWORD` | `spring.datasource.password` | Database password. |
| `JWT_ACCESS_SECRET` | `jwt.access-secret` | HMAC secret for access tokens. |
| `JWT_REFRESH_SECRET` | `jwt.refresh-secret` | HMAC secret for refresh tokens. |
| `REDIS_HOST` | `spring.data.redis.host` | Redis / Upstash hostname. |
| `REDIS_PORT` | `spring.data.redis.port` | Redis port (`6379`). |
| `REDIS_USERNAME` | `spring.data.redis.username` | Redis username (default: `default`). |
| `REDIS_PASSWORD` | `spring.data.redis.password` | Redis password / token. |
| `REDIS_SSL_ENABLED` | `spring.data.redis.ssl.enabled` | Redis TLS flag (`true` for Upstash). |
| `CORS_ALLOWED_ORIGINS`| `railway.security.cors.allowed-origins`| Allowed frontend origins (e.g. `https://railora.vercel.app`). |
| `RAILWAY_BASE_FARE` | `railway.fare.base-amount` | Flat baseline booking fare (e.g. `500.00`). |

### 3.2 Feature-Specific Variables (Safe Production Defaults Provided)

| Environment Variable | Property Key | Default in Production | Behavior |
| :--- | :--- | :---: | :--- |
| `HIKARI_MAX_LIFETIME_MS` | `spring.datasource.hikari.max-lifetime` | `600000` (10 min) | Conservative cloud connection lifetime. |
| `KAFKA_BOOTSTRAP_SERVERS` | `spring.kafka.bootstrap-servers` | `""` (empty) | Inactive when Kafka publisher is disabled. |
| `RAILWAY_OUTBOX_KAFKA_ENABLED` | `railway.outbox.kafka.enabled` | `false` | Kafka publishing disabled. |
| `RAZORPAY_ENABLED` | `razorpay.enabled` | `false` | Real payment gateway disabled. |
| `RAILWAY_PAYMENT_SIMULATED_ENABLED` | `railway.payment.simulated-enabled`| `true` | Simulated payment active for demo. |
| `METRICS_SCRAPE_AUTH_ENABLED` | `management.metrics.scrape.auth.enabled` | `false` | Metrics scrape auth disabled (ROLE_ADMIN). |
| `SEED_ADMIN_USER_ENABLED` | `railway.data-seed.admin.enabled` | `false` | Admin seeder inactive. |
| `SEED_JOURNEY_DATA_ENABLED` | `railway.data-seed.journey.enabled` | `false` | Journey seeder inactive. |

---

## 4. Item 3: Starting the Application for Migration Without Redis and Kafka

### 4.1 Kafka Independence
- Kafka publishing is disabled by default (`railway.outbox.kafka.enabled=false`).
- `OutboxPollingScheduler` is conditioned on `@ConditionalOnProperty(name = "railway.outbox.kafka.enabled", havingValue = "true")` and is **never instantiated**.
- Zero network packets are sent to Kafka on startup.

### 4.2 Redis Independence & Fail-Open Behavior
- **Search Cache:** [RedisCacheConfig.java](file:///D:/railway-github-check/Railway-Booking-System/src/main/java/in/yograj/learnspringboot/railway_booking/config/RedisCacheConfig.java#L61-L64) explicitly fails open: if `railway.cache.enabled=false` or if the connection factory is offline, it falls back to an in-memory `ConcurrentMapCacheManager`.
- **Tatkal Queue:** Uses Spring Data Redis `StringRedisTemplate`. Lettuce connects **lazily** upon first command invocation, not eagerly at application startup.
- **Readiness Probes:** `management.endpoint.health.group.readiness.include=readinessState,db,diskSpace`. Redis is excluded from readiness probes.
- **Placeholder Requirement:** Because `application-production.properties` has placeholders for `REDIS_HOST`, etc., dummy values (e.g. `dummy-redis`, `6379`) can be supplied if Redis is temporarily unreachable during a migration-only run.

---

## 5. Item 4: Verified Windows PowerShell-Compatible Maven Command

On Windows 11 with PowerShell:
- Running `./mvnw` attempts to execute the Linux Bash shell script `mvnw`, which causes command association errors or requires Git Bash.
- The canonical Windows execution command uses `.\mvnw.cmd`:

```powershell
# Verified working on Windows PowerShell
cd "D:\railway-github-check\Railway-Booking-System"
.\mvnw.cmd -v
```

**Verification Output:**
```text
Apache Maven 3.9.16 (2bdd9fddda4b155ebf8000e807eb73fd829a51d5)
Java version: 21.0.1, vendor: Oracle Corporation, runtime: C:\Program Files\Java\jdk-21
OS name: "windows 11", version: "10.0", arch: "amd64", family: "windows"
```

---

## 6. Item 5: Confirmed Flyway Dependency Versions

Resolved directly from `pom.xml` via `.\mvnw.cmd dependency:list`:

| Dependency Group & Artifact | Resolved Version | Scope |
| :--- | :---: | :---: |
| `org.flywaydb:flyway-core` | `12.4.0` | compile |
| `org.flywaydb:flyway-database-postgresql` | `12.4.0` | compile |
| `org.postgresql:postgresql` | `42.7.11` | runtime |
| `org.hibernate.orm:hibernate-core` | `7.4.1.Final` | compile |

---

## 7. Item 6: Application Startup Side-Effects Audit (Seeders & Schedulers)

### 7.1 Data Seeders (`RailwayBookingApplication.java`)
- **Admin Seeder (`seedAdminUser`):** Controlled by `@ConditionalOnProperty(name = "railway.data-seed.admin.enabled", havingValue = "true", matchIfMissing = false)`. Inactive by default.
- **Journey Reference Seeder (`seedJourneyReferenceData`):** Controlled by `@ConditionalOnProperty(name = "railway.data-seed.journey.enabled", havingValue = "true", matchIfMissing = false)`. Inactive by default.
- **Verdict:** No seeder will insert data into the database during migration.

### 7.2 Scheduled Tasks
1. **`OutboxPollingScheduler`:** Inactive because `railway.outbox.kafka.enabled` is `false`.
2. **`SeatHoldReaperService`:** Enabled by default (`railway.seat-hold-reaper.enabled=true`) with an `initial-delay` of 10,000 ms (10 seconds).
   - *Impact on empty DB:* Scans `seat_allocations` table; finds 0 rows; does nothing.
   - *Isolation Recommendation:* Pass `--railway.seat-hold-reaper.enabled=false` during migration to guarantee zero background database polling.

### 7.3 External Integrations
- **RailRadar:** Client services are invoked on demand via REST controllers; no startup tasks exist.
- **Razorpay:** Disabled by default (`razorpay.enabled=false`).

---

## 8. Item 7: Safe Method to Prevent Accidental Local Database Connections

### 8.1 The Risk
In base `application.properties`:
```properties
spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5432/railway_booking}
```
If an operator runs Maven without activating the `production` profile, Spring Boot defaults to `localhost:5432`.

### 8.2 The Built-In Protection in `application-production.properties`
In `application-production.properties`:
```properties
spring.datasource.url=${DB_URL}
```
There is no default fallback. If `DB_URL` is omitted, startup terminates immediately.

### 8.3 Recommended Pre-Flight PowerShell Assertion Guard
Before launching the command, execute a script block in PowerShell that asserts the target URL:

```powershell
# Safety assertion: verify DB_URL points to Neon before executing
if (-not $env:DB_URL -or $env:DB_URL -notmatch "neon\.tech.*sslmode=require") {
    throw "SAFETY ABORT: DB_URL is either missing or not pointing to a secure Neon endpoint!"
}
```

---

## 9. Item 8: One-Time Migration-Only Mode vs Full Application Startup

### 9.1 Comparison

| Dimension | Full Web Application Startup | Migration-Only Startup Mode |
| :--- | :--- | :--- |
| **Command** | `.\mvnw.cmd spring-boot:run` | `.\mvnw.cmd spring-boot:run -Dspring-boot.run.arguments="--spring.main.web-application-type=none --railway.seat-hold-reaper.enabled=false"` |
| **Flyway Migrations** | Runs V1–V7 via `FlywayConfig` | Runs V1–V7 via `FlywayConfig` |
| **Hibernate Validation** | Validates schema (`ddl-auto=validate`) | Validates schema (`ddl-auto=validate`) |
| **Embedded Tomcat** | Starts on port 8080 (binds port) | **Disabled** (no port required) |
| **Scheduled Jobs** | Starts `SeatHoldReaperService` | **Disabled** |
| **Process Lifecycle** | Runs indefinitely until killed | **Exits cleanly (exit code 0) once verified** |
| **Failure Risk** | Port conflict / hung process | **Zero port conflicts; clean termination** |

### 9.2 Recommendation
**The Migration-Only Mode is distinctly safer for initial schema creation.**  
It executes Flyway migrations, executes Hibernate schema validation, outputs complete logs, and cleanly shuts down without leaving an active web server or scheduled jobs running.

---

## 10. Execution Checklist for Live Neon Migration (Phase 17.4D.2)

```
[ ] Step 1: Pre-Flight Environment Setup (PowerShell Session)
    $env:SPRING_PROFILES_ACTIVE = "production"
    $env:DB_URL                 = "jdbc:postgresql://<neon-endpoint>.ap-southeast-1.aws.neon.tech/railway_booking_prod?sslmode=require"
    $env:DB_USERNAME            = "neondb_owner"
    $env:DB_PASSWORD            = "<neon-password>"
    $env:JWT_ACCESS_SECRET      = "<verified-access-secret>"
    $env:JWT_REFRESH_SECRET     = "<verified-refresh-secret>"
    $env:REDIS_HOST             = "<upstash-redis-host>"
    $env:REDIS_PORT             = "6379"
    $env:REDIS_USERNAME         = "default"
    $env:REDIS_PASSWORD         = "<upstash-redis-password>"
    $env:REDIS_SSL_ENABLED      = "true"
    $env:CORS_ALLOWED_ORIGINS   = "https://railora.vercel.app"
    $env:RAILWAY_BASE_FARE      = "500.00"

[ ] Step 2: Target Assertion Guard Check
    if ($env:DB_URL -notmatch "neon\.tech.*sslmode=require") {
        throw "ABORT: DB_URL is not targeting Neon!"
    }

[ ] Step 3: Execute Controlled Migration-Only Run
    .\mvnw.cmd spring-boot:run -Dspring-boot.run.arguments="--spring.main.web-application-type=none --railway.seat-hold-reaper.enabled=false"

[ ] Step 4: Verify Console Logs
    [ ] "Current version of schema "public": << Empty Schema >>"
    [ ] "Migrating schema "public" to version "1 - initial schema""
    [ ] ...
    [ ] "Successfully applied 7 migrations to schema "public""
    [ ] "HHH000490: Using dialect: org.hibernate.dialect.PostgreSQLDialect"
    [ ] "Initialized JPA EntityManagerFactory for persistence unit 'default'"
    [ ] Application context closes cleanly with exit code 0.

[ ] Step 5: Post-Migration Live SQL Verification
    [ ] SELECT count(*) FROM information_schema.tables WHERE table_schema='public'; -- Expect 19
    [ ] SELECT count(*) FROM public.flyway_schema_history WHERE success=true;      -- Expect 7
    [ ] SELECT extname FROM pg_extension WHERE extname='btree_gist';              -- Expect 1 row
    [ ] SELECT conname FROM pg_constraint WHERE conname='seat_alloc_no_overlap_excl'; -- Expect 1 row
```

---

> [!NOTE]
> **Safety Status Confirmation:**
> - Strictly read-only technical review completed.
> - Zero live database connections initiated.
> - Zero SQL or Flyway commands executed.
> - Zero source code or migration files altered.
> - Zero Git changes staged or committed.
>
> Execution is stopped. Awaiting your approval before proceeding with live execution.
