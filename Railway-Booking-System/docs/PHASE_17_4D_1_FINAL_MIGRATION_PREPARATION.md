# Railora — Phase 17.4D.1: Final Migration Preparation & Technical Audit Report

**Date:** 2026-11-03  
**Target Architecture:** Vercel (Frontend) + Render (Backend Docker) + Neon (PostgreSQL 18.6) + Upstash (Redis)  
**Audit Type:** Repository-Only Technical Review & Migration Preflight  
**Mode:** Strictly Read-Only (Zero Live Network Connections, Zero SQL Executions, No Flyway Execution, No Code Modification)  
**Canonical Repository:** `D:\railway-github-check\Railway-Booking-System`  
**Git Root:** `D:\railway-github-check` (Branch: `main`)  

---

## 1. Executive Summary & Verified Live Preflight Status

Prior to initiating any live schema migration against the production Neon PostgreSQL database, this audit establishes technical guarantees across the Flyway migration engine, database dialect compatibility, transactional migration boundaries, and Spring Boot datasource orchestration.

### 1.1 Verified Live Neon Database Configuration (User Preflight Baseline)
The live target Neon database was inspected via manual read-only SQL queries with the following certified parameters:

| Parameter | Live Value | Verification Source |
| :--- | :--- | :--- |
| **Neon Project** | `railora-production` | Neon Console / Project Spec |
| **Neon Branch** | `production` | Neon Console / Branch Spec |
| **Database Name** | `railway_booking_prod` | `SELECT current_database();` -> `railway_booking_prod` |
| **Active Role** | `neondb_owner` | `SELECT current_user;` -> `neondb_owner` |
| **Database Version** | `PostgreSQL 18.6` | `SELECT version();` -> PostgreSQL 18.6 |
| **Region** | AWS Singapore (`ap-southeast-1`) | Cloud Topology Specification |
| **Connection Type** | Direct JDBC (Connection Pooling OFF) | Endpoint Specification (no PgBouncer port 6543) |
| **SSL Enforcement** | Required (`sslmode=require`) | Neon Architecture Requirement |
| **Public Schema Tables** | 0 tables | `SELECT count(*) FROM information_schema.tables WHERE table_schema='public';` -> `0` |
| **Flyway Schema History** | Does not exist | `to_regclass('public.flyway_schema_history')` -> `NULL` |
| **Extension Availability** | `btree_gist` available (v1.8) | `SELECT default_version, installed_version FROM pg_available_extensions WHERE name='btree_gist';` |
| **Extension Properties** | `trusted = true`, `superuser = true` | `SELECT trusted, superuser FROM pg_available_extension_versions WHERE name='btree_gist';` |
| **Schema Permissions** | `CREATE` privilege confirmed | `SELECT has_database_privilege(current_user, current_database(), 'CREATE');` -> `true` |
| **Current Extension State**| Not yet installed | `SELECT * FROM pg_extension WHERE extname='btree_gist';` -> `0 rows` |

---

## 2. PASS / WARNING / BLOCKED Status Matrix

| Domain | Status | Key Evidence & Impact |
| :--- | :---: | :--- |
| **Flyway Engine Compatibility** | **PASS** | `flyway-core:12.4.0` bytecode disassembles to `recommendFlywayUpgradeIfNecessaryForMajorVersion("18")`. Version 18 is natively accepted without warning or error. |
| **Hibernate Dialect Compatibility** | **PASS** | `hibernate-core:7.4.1.Final` auto-resolves `PostgreSQLDialect` from JDBC metadata; fully supports identity columns, generated stored columns, and range types. |
| **V1 Schema Script** | **PASS** | 14 core tables, standard relational types, identity columns, foreign keys. 100% transactional DDL. |
| **V2 btree_gist & GiST Exclusion** | **PASS** | `btree_gist` is confirmed trusted (v1.8) and `neondb_owner` holds `CREATE` privilege. Range generator and exclusion constraint are 100% transactional. |
| **V3 Tatkal Allocation Schema** | **PASS** | Structural columns, CHECK constraints, and partial index. 100% transactional DDL. |
| **V4 PNR Schema & CTE Backfill** | **PASS** | Deterministic `ROW_NUMBER()` CTE backfill touches 0 rows on clean database. Unique constraint and check constraint. 100% transactional. |
| **V5 Outbox Schema** | **PASS** | Relational outbox and consumer deduplication tables. Independent of live Kafka brokers. 100% transactional. |
| **V6 Outbox Claims Indexing** | **PASS** | Adds locking columns and retry scheduler composite index. 100% transactional. |
| **V7 Payment & Webhook Schema** | **PASS** | Nullable gateway references compatible with simulated payment mode. Webhook and refund tables. 100% transactional. |
| **Datasource & HikariCP Configuration** | **PASS** | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` mapped. Conservative cloud `max-lifetime=600000ms` (10 min) configured for Neon idle boundaries. |
| **Flyway & Hibernate Bean Ordering** | **PASS** | `FlywayConfig.java` enforces `@Bean(initMethod = "migrate")` and `EntityManagerFactoryDependsOnPostProcessor(Flyway.class)`. |
| **Hibernate Validation Mode** | **PASS** | `spring.jpa.hibernate.ddl-auto=validate` strictly checks schema post-migration without altering DDL. |

---

## 3. Task 1: Flyway & Database Compatibility Audit

### 3.1 Exact Resolved Dependency Versions
Inspected from `pom.xml` and Maven resolved dependency graph:

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.0</version>
</parent>
```

- **Spring Boot:** `4.1.0`
- **Flyway Core:** `org.flywaydb:flyway-core:12.4.0`
- **Flyway PostgreSQL Extension:** `org.flywaydb:flyway-database-postgresql:12.4.0`
- **PostgreSQL JDBC Driver:** `org.postgresql:postgresql:42.7.11`
- **Hibernate Core:** `org.hibernate.orm:hibernate-core:7.4.1.Final`

### 3.2 Bytecode Disassembly & Support Verification
To avoid making assumptions based merely on the JDBC driver, direct bytecode disassembly of `org.flywaydb.database.postgresql.PostgreSQLDatabase` (packaged in `flyway-database-postgresql-12.4.0.jar`) was performed:

```java
// Method: org.flywaydb.database.postgresql.PostgreSQLDatabase.ensureSupported(Configuration)
public void ensureSupported(Configuration configuration) {
    ensureDatabaseIsRecentEnough("9.0");
    ensureDatabaseNotOlderThanOtherwiseRecommendUpgradeToFlywayEdition("10", Tier.PREMIUM, configuration);
    recommendFlywayUpgradeIfNecessaryForMajorVersion("18");
}
```

Disassembly of `org.flywaydb.core.internal.database.base.Database.recommendFlywayUpgradeIfNecessaryForMajorVersion(String majorVersion)`:

```java
public void recommendFlywayUpgradeIfNecessaryForMajorVersion(String majorVersion) {
    if (getVersion().isMajorNewerThan(majorVersion)) {
        LOG.warn(String.format(
            "Flyway upgrade recommended: %s %s is newer than the version Flyway has been verified with (%s)...",
            getDatabaseType().getName(),
            getVersion().getMajorAsString(),
            majorVersion
        ));
    }
}
```

### 3.3 Compatibility Verdict
1. **Flyway Support:** For PostgreSQL version `18.6`, `getVersion().isMajorNewerThan("18")` evaluates to **`false`**. Flyway 12.4.0 has established explicit verification for PostgreSQL major version 18. Even if a hypothetical future sub-version evaluated to newer, Flyway issues an informational log warning and **does not throw a fatal startup exception**.
2. **Hibernate 7.4.1.Final:** Uses `PostgreSQLDialect` which natively supports:
   - Modern `GENERATED BY DEFAULT AS IDENTITY` syntax.
   - Range types (`int4range`) and GiST index definitions.
   - Generated stored columns (`STORED`).
   - JSONB and modern PostgreSQL catalog metadata.
3. **Required Changes:** **None**. The resolved dependencies in the repository fully support PostgreSQL 18.6 without version upgrades or dependency substitutions.

---

## 4. Task 2: Migration-by-Migration Technical Audit (V1–V7)

### 4.1 Script Analysis Matrix

| Migration Script | Operations & Features | Transactional? | PG 18 Compatibility | Risk Assessment |
| :--- | :--- | :---: | :---: | :--- |
| **`V1__initial_schema.sql`** | 14 tables, identity columns, foreign keys, unique constraints, standard B-tree indexes. | Yes | Verified | Zero risk on clean database. Pure transactional DDL. |
| **`V2__add_segment_range_and_gist_exclusion.sql`** | `CREATE EXTENSION IF NOT EXISTS btree_gist;`<br>PL/pgSQL data integrity validation block.<br>Sequence columns addition.<br>`INT4RANGE ... GENERATED ALWAYS AS (int4range(from_stop_seq, to_stop_seq, '[)')) STORED`.<br>`EXCLUDE USING gist (inventory_id WITH =, segment_range WITH &&)`.<br>Partial index on `hold_expires_at`. | Yes | Verified | `btree_gist` is trusted and `neondb_owner` has `CREATE` privileges. PL/pgSQL validation processes 0 rows. Pure transactional DDL. |
| **`V3__add_tatkal_admission_to_seat_allocations.sql`** | `booking_mode` column, `admission_entry_id` column, CHECK constraints, partial index where `admission_entry_id IS NOT NULL`. | Yes | Verified | Standard structural additions. Fully transactional. |
| **`V4__add_pnr_to_bookings.sql`** | Add `pnr VARCHAR(10)`, CTE backfill using `ROW_NUMBER() OVER (ORDER BY booking_id ASC)`, NOT NULL, `uk_bookings_pnr` unique constraint. | Yes | Verified | On an empty table, the CTE touches 0 rows instantly. Fully transactional DDL/DML. |
| **`V5__create_outbox_events.sql`** | `outbox_events` table, `processed_consumer_events` table, identity column, unique constraints, indexes. | Yes | Verified | Relational transactional outbox schema. Operates entirely in PostgreSQL; zero dependency on Kafka brokers. Fully transactional. |
| **`V6__add_outbox_claims_and_retry_schedule.sql`** | Add `claim_token`, `claimed_at`, `next_attempt_at` columns, composite index `idx_outbox_events_retry_schedule`. | Yes | Verified | Structural additions. Fully transactional. |
| **`V7__add_razorpay_payment_references.sql`** | Gateway tracking columns on `payment_transactions`, unique constraints, `razorpay_webhook_events` table, `razorpay_refunds` table with foreign key. | Yes | Verified | Nullable columns allow seamless operation in simulated payment mode (`railway.payment.simulated-enabled=true`). Fully transactional. |

### 4.2 Key Invariants & Edge Cases

1. **Identity Columns vs Sequences:**
   All tables use standard SQL `GENERATED BY DEFAULT AS IDENTITY` (backed by PostgreSQL sequences). Under PostgreSQL transactional DDL, if an entire migration transaction rolls back, structural table creation rolls back cleanly. If any DML had occurred, sequence counters do not roll back; however, because this is an initial migration onto an empty database with no application data insertions, sequence drift cannot occur.
2. **`btree_gist` Extension Installation:**
   In PostgreSQL, `CREATE EXTENSION` is transactional and can execute inside Flyway's migration transaction. Because the live check verified `trusted=true` and `has_database_privilege(neondb_owner, ..., 'CREATE')=true`, `neondb_owner` is authorized to install `btree_gist` into the database.
3. **Generated Stored Column & GiST Exclusion:**
   `segment_range INT4RANGE GENERATED ALWAYS AS (int4range(from_stop_seq, to_stop_seq, '[)')) STORED` and `EXCLUDE USING gist (inventory_id WITH =, segment_range WITH &&)` represent Railora's core inventory concurrency invariant. Both features are fully supported in PostgreSQL 18.6.

---

## 5. Task 3: Production Datasource & Runtime Verification

### 5.1 Datasource Binding Verification
Inspected in [application-production.properties](file:///D:/railway-github-check/Railway-Booking-System/src/main/resources/application-production.properties) and [application.properties](file:///D:/railway-github-check/Railway-Booking-System/src/main/resources/application.properties):

- **Database URL:** `spring.datasource.url=${DB_URL}` (No hardcoded default in production; fails fast if unset).
- **Username:** `spring.datasource.username=${DB_USERNAME}`.
- **Password:** `spring.datasource.password=${DB_PASSWORD}`.
- **Driver:** `org.postgresql.Driver` (defined in base `application.properties`).
- **Direct Neon Connection Compatibility:** Direct JDBC connection strings use format:
  `jdbc:postgresql://<neon-endpoint>.ap-southeast-1.aws.neon.tech/railway_booking_prod?sslmode=require`
  This connects directly to Postgres compute without PgBouncer session pooling, preserving support for transactional DDL, prepared statements, and session locks.

### 5.2 HikariCP Cloud Connection Pool Configuration
Configured in `application-production.properties` (Phase 17.4B remediation) and `application.properties`:

- `spring.datasource.hikari.max-lifetime=${HIKARI_MAX_LIFETIME_MS:600000}` (10 minutes).
  - *Rationale:* Safely below Neon compute idle scale-to-zero (15 minutes) and AWS intermediate NAT timeout (350 seconds for inactive TCP, while active pool maintenance keeps live sockets refreshed).
- `spring.datasource.hikari.maximum-pool-size=${HIKARI_MAX_POOL_SIZE:15}`.
- `spring.datasource.hikari.minimum-idle=${HIKARI_MIN_IDLE:5}`.
- `spring.datasource.hikari.connection-timeout=${HIKARI_CONNECTION_TIMEOUT_MS:20000}` (20 seconds).
- `spring.datasource.hikari.idle-timeout=${HIKARI_IDLE_TIMEOUT_MS:300000}` (5 minutes).
- `spring.datasource.hikari.leak-detection-threshold=${HIKARI_LEAK_DETECTION_THRESHOLD_MS:20000}` (20 seconds).

### 5.3 Flyway-Before-Hibernate Initialization Guarantee
Configured in [FlywayConfig.java](file:///D:/railway-github-check/Railway-Booking-System/src/main/java/in/yograj/learnspringboot/railway_booking/config/FlywayConfig.java):

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true", matchIfMissing = true)
public class FlywayConfig {

    @Bean(initMethod = "migrate")
    public Flyway flyway(
            DataSource dataSource,
            @Value("${spring.flyway.locations:classpath:db/migration}") String locations,
            @Value("${spring.flyway.baseline-on-migrate:false}") boolean baselineOnMigrate,
            ObjectProvider<RazorpayE2eDataSourceVerifier> e2eDataSourceVerifierProvider) {
        e2eDataSourceVerifierProvider.getIfAvailable();
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(locations)
                .baselineOnMigrate(baselineOnMigrate)
                .load();
    }

    @Bean
    public static EntityManagerFactoryDependsOnPostProcessor entityManagerFactoryDependsOnFlywayPostProcessor() {
        return new EntityManagerFactoryDependsOnPostProcessor(Flyway.class);
    }
}
```

- `EntityManagerFactoryDependsOnPostProcessor(Flyway.class)` guarantees that the Spring container initializes the Flyway bean and triggers its `initMethod = "migrate"` **before** creating the JPA `EntityManagerFactory`.
- `spring.jpa.hibernate.ddl-auto=validate` validates entity mappings against the database schema only **after** all Flyway migrations V1–V7 have successfully executed.

---

## 6. Task 4: Controlled Migration Execution Plan

### 6.1 Phase A: Pre-Flight Sanity Check
Before launching the application migration command, the operator can confirm database cleanliness and identity via read-only SQL:

```sql
-- 1. Verify target database and user
SELECT current_database(), current_user, version();

-- 2. Verify schema cleanliness (must return 0)
SELECT count(*) AS table_count 
FROM information_schema.tables 
WHERE table_schema = 'public';

-- 3. Verify absence of prior Flyway execution (must return NULL)
SELECT to_regclass('public.flyway_schema_history') AS flyway_history;
```

### 6.2 Phase B: Controlled Spring Boot Flyway Execution Command
Execute the application migration using the production profile with runtime environment variables supplied via secure shell/console environment (never hardcoded in files):

```bash
# Example execution command for controlled migration run
export SPRING_PROFILES_ACTIVE=production
export DB_URL="jdbc:postgresql://<neon-host>/railway_booking_prod?sslmode=require"
export DB_USERNAME="neondb_owner"
export DB_PASSWORD="<neon-password>"
export JWT_ACCESS_SECRET="<verified-access-secret>"
export JWT_REFRESH_SECRET="<verified-refresh-secret>"
export REDIS_HOST="<upstash-redis-host>"
export REDIS_PORT="6379"
export REDIS_PASSWORD="<upstash-redis-password>"
export REDIS_SSL_ENABLED="true"
export CORS_ALLOWED_ORIGINS="https://railora.vercel.app"
export RAILWAY_BASE_FARE="500.00"

./mvnw spring-boot:run -Dspring-boot.run.arguments="--server.port=8080"
```

*Note: In containerized deployment on Render, these variables are provided as Render Environment Variables, and Docker container startup runs the compiled jar.*

### 6.3 Phase C: Post-Migration Database Verification Queries
After execution, verify database state with the following inspection queries:

#### 1. Verify Flyway Schema History (Must show 7 successful migrations)
```sql
SELECT installed_rank, version, description, type, script, execution_time, success
FROM public.flyway_schema_history
ORDER BY installed_rank ASC;
```
*Expected Result:* 7 rows with versions `1`, `2`, `3`, `4`, `5`, `6`, `7`, all with `success = true`.

#### 2. Verify Table Inventory (Must return 19 tables in public schema)
```sql
SELECT table_name 
FROM information_schema.tables 
WHERE table_schema = 'public' 
ORDER BY table_name;
```
*Expected Inventory (18 application tables + 1 Flyway history table):*
1. `bookings`
2. `coaches`
3. `flyway_schema_history`
4. `journey_seat_inventory`
5. `journey_stops`
6. `journeys`
7. `outbox_events`
8. `payment_transactions`
9. `processed_consumer_events`
10. `razorpay_refunds`
11. `razorpay_webhook_events`
12. `refresh_tokens`
13. `seat_allocations`
14. `seats`
15. `stations`
16. `train_routes`
17. `train_stops`
18. `trains`
19. `users`

#### 3. Verify `btree_gist` Extension Installation
```sql
SELECT extname, extversion 
FROM pg_extension 
WHERE extname = 'btree_gist';
```
*Expected Result:* 1 row showing `btree_gist` installed.

#### 4. Verify GiST Exclusion Constraint on `seat_allocations`
```sql
SELECT conname, contype, pg_get_constraintdef(oid) 
FROM pg_constraint 
WHERE conname = 'seat_alloc_no_overlap_excl';
```
*Expected Result:* `EXCLUDE USING gist (inventory_id WITH =, segment_range WITH &&) WHERE ((status = ANY (ARRAY['HOLD'::character varying, 'BOOKED'::character varying]::text[])))`.

#### 5. Verify Hibernate Schema Validation
Check application startup logs for:
```text
HHH000490: Using dialect: org.hibernate.dialect.PostgreSQLDialect
...
Initialized JPA EntityManagerFactory for persistence unit 'default'
```
*Expected Result:* Application completes context refresh and reports readiness probe `UP` with zero schema mismatch exceptions.

### 6.4 Failure Recovery Protocol
If any migration step encounters an unexpected error:
1. **Transactional Rollback:** PostgreSQL rolls back the failed script's DDL and DML. Previously committed scripts (e.g. V1 if V2 failed) remain committed.
2. **Flyway State:** Flyway records the failed version in `flyway_schema_history` with `success = false`.
3. **Recovery Sequence:**
   - **Step 1:** Inspect the application error log to identify the exact SQL exception and line number.
   - **Step 2:** Query `SELECT * FROM public.flyway_schema_history WHERE success = false;`.
   - **Step 3:** Do **NOT** delete or drop tables manually. Diagnose root cause (e.g., permissions or network interruption).
   - **Step 4:** Once the root issue is resolved in the environment, run Flyway repair (`flyway:repair` or `./mvnw flyway:repair` with datasource parameters) to remove the failed migration record from history.
   - **Step 5:** Re-execute the application startup to retry the migration.

---

## 7. Task 5: Non-Destructive Offline Pre-Flight Tests

The repository contains unit and configuration slice tests that validate Flyway ordering and production properties completely offline, without connecting to any database:

1. **`ProductionPropertiesValidationTests`**
   - Path: [ProductionPropertiesValidationTests.java](file:///D:/railway-github-check/Railway-Booking-System/src/test/java/in/yograj/learnspringboot/railway_booking/config/ProductionPropertiesValidationTests.java)
   - *Validates:* Mandatory environment variable placeholders (`${DB_URL}`, `${DB_USERNAME}`, `${DB_PASSWORD}`), HikariCP cloud `max-lifetime` defaults, JWT and Redis configuration placeholders, absence of hardcoded production secrets.
   - *Execution Mode:* In-memory property resolution; zero database or network calls.
2. **`FlywayConfigTests`**
   - Path: [FlywayConfigTests.java](file:///D:/railway-github-check/Railway-Booking-System/src/test/java/in/yograj/learnspringboot/railway_booking/config/FlywayConfigTests.java)
   - *Validates:* Instantiation of the `Flyway` bean and registration of `EntityManagerFactoryDependsOnPostProcessor` using an isolated in-memory test runner.
   - *Execution Mode:* Offline context runner; zero external database interaction.

---

## 8. Remaining Uncertainties & Operational Safeguards

1. **Cold Compute Latency (Scale to Zero):** Neon compute branches scale to zero when idle. The first connection after a dormant period may take 2–4 seconds to resume compute. HikariCP `connection-timeout = 20000ms` (20s) accommodates compute wake-up cleanly.
2. **Single Free Tier Manual Snapshot Limit:** Neon Free tier allows 1 manual branch snapshot and 6 hours of PITR. Once migrations complete, creating a branch snapshot in Neon Console will safeguard the initial baseline schema.
3. **Readiness Probe Transition:** On first boot with an empty schema, Flyway migrations run before the web server begins serving traffic. Render's health check probe timeout should be set to at least 60–90 seconds to allow migration completion and initial entity validation without triggering premature container restarts.

---

## 9. Conclusion & Action Request

All repository checks, bytecode analyses, migration SQL evaluations, and datasource configurations are **COMPLETE and VERIFIED**.
The repository is in a **PASS** state and technically ready for the live Flyway migration execution against Neon PostgreSQL 18.6.

> [!IMPORTANT]
> **Strict Safety Hold:** In adherence to Phase 17.4D.1 constraints, no live connection to Neon has been initiated and no migrations have been triggered.
> 
> Please review this audit report and provide explicit approval to proceed to the next phase (**Phase 17.4D.2: Live Neon Migration Execution**).
