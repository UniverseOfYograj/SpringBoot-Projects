# Railora Phase 17.4C — Final Neon Migration Preflight & Safety Audit

**Date:** 2026-11-03 (Updated under Phase 17.4C.1 Audit Findings Correction)  
**Target Architecture:** Render (Backend) + Neon PostgreSQL (`railora-production`, direct connection)  
**Target Database:** `railway_booking_prod` (Branch: `production`, Region: AWS Singapore `ap-southeast-1`)  
**Target Role:** `neondb_owner`  
**Repository Path:** `D:\railway-github-check\Railway-Booking-System`  
**Audit Execution Mode:** Strictly READ-ONLY (No external network sockets, no SQL execution, no migrations, zero secrets accessed)  

---

## 1. Executive Summary

This preflight audit evaluates Railora's readiness to execute **Flyway database migrations V1 through V7** against the newly provisioned **Neon PostgreSQL** project `railora-production` (`railway_booking_prod`).

The target database is expected to be newly created and completely empty. All previous phases (17.3A–17.3D, 17.4A, and 17.4B) have been reviewed:
- `docs/PHASE_17_3A_CLOUD_DEPLOYMENT_PREFLIGHT.md`
- `docs/PHASE_17_3B_CLOUD_REMEDIATION.md`
- `docs/PHASE_17_4A_NEON_COMPATIBILITY_AUDIT.md`
- `docs/PHASE_17_4B_CLOUD_CONFIGURATION_REMEDIATION.md`

### Strict Epistemic Scope of this Audit
This repository audit is strictly **offline and read-only**. It verifies the structural integrity, DDL syntax, sequence, and configuration in the codebase. By definition, **no read-only repository audit can verify the live state of the remote Neon database**. Remote database emptiness, actual runtime role privileges of `neondb_owner`, and the presence or absence of `flyway_schema_history` cannot be confirmed until a controlled live preflight connection is established in Phase 17.4D.

### Final Preflight Verdict: **READY WITH CONDITIONS**

The Railora database schema, Flyway scripts, and Spring Boot startup lifecycle are structurally compatible with PostgreSQL 15/16/17 on Neon in Direct Connection mode. No blocking code defects, missing migration files, or duplicate migration versions exist. 

Migration execution must **NOT** be run blindly. It can proceed safely only after the **preflight verification conditions** detailed in Section 10 are satisfied against the live database and explicit operator approval is granted.

---

## 2. PASS / WARNING / BLOCKED Preflight Audit Matrix

| Check Area | Status | Key Evidence & File References |
|---|:---:|---|
| **A. Migration Inventory** | **PASS** | 7 sequential files (V1–V7); 0 duplicates, 0 missing versions, 0 repeatable migrations, 0 destructive statements (`DROP`/`TRUNCATE`/`DELETE`), 0 hardcoded hosts/DBs. |
| **B. V1 — Initial Schema** | **PASS** | [`V1__initial_schema.sql`](file:///D:/railway-github-check/Railway-Booking-System/src/main/resources/db/migration/V1__initial_schema.sql): 14 baseline tables; standard identity generators; foreign keys; unique constraints; clean table dependencies. |
| **C. V2 — Segment Range & GiST Exclusion** | **WARNING** | [`V2__add_segment_range_and_gist_exclusion.sql`](file:///D:/railway-github-check/Railway-Booking-System/src/main/resources/db/migration/V2__add_segment_range_and_gist_exclusion.sql): `btree_gist` extension; `INT4RANGE` generated stored column; partial GiST exclusion constraint `seat_alloc_no_overlap_excl`. **Warning:** Extension creation privilege for `neondb_owner` is pending live verification. |
| **D. V3 — Tatkal Admission** | **PASS** | [`V3__add_tatkal_admission_to_seat_allocations.sql`](file:///D:/railway-github-check/Railway-Booking-System/src/main/resources/db/migration/V3__add_tatkal_admission_to_seat_allocations.sql): Check constraints and partial index `idx_seat_allocation_admission_entry`. |
| **E. V4 — Numeric PNR** | **PASS** | [`V4__add_pnr_to_bookings.sql`](file:///D:/railway-github-check/Railway-Booking-System/src/main/resources/db/migration/V4__add_pnr_to_bookings.sql): 10-digit numeric PNR; CTE `ROW_NUMBER()` backfill; unique constraint `uk_bookings_pnr`. |
| **F. V5 & V6 — Transactional Outbox** | **PASS** | [`V5__create_outbox_events.sql`](file:///D:/railway-github-check/Railway-Booking-System/src/main/resources/db/migration/V5__create_outbox_events.sql) & [`V6__add_outbox_claims_and_retry_schedule.sql`](file:///D:/railway-github-check/Railway-Booking-System/src/main/resources/db/migration/V6__add_outbox_claims_and_retry_schedule.sql): Outbox and consumer event tables; claim token; retry index; **zero Kafka dependency during migration**. |
| **G. V7 — Razorpay References** | **PASS** | [`V7__add_razorpay_payment_references.sql`](file:///D:/railway-github-check/Railway-Booking-System/src/main/resources/db/migration/V7__add_razorpay_payment_references.sql): Nullable gateway IDs; refund and webhook tables; 100% compatible with simulated payment mode and disabled Razorpay. |
| **H. Spring Boot & Flyway Lifecycle** | **PASS** | [`FlywayConfig.java`](file:///D:/railway-github-check/Railway-Booking-System/src/main/java/in/yograj/learnspringboot/railway_booking/config/FlywayConfig.java#L21-L38): PostProcessor enforces Flyway migrations strictly before Hibernate validation. |
| **I. Direct Connection & SSL** | **PASS** | Direct JDBC connection (pooling OFF); mandatory `sslmode=require`; `channel_binding=require` omitted in favor of driver default `prefer`. |
| **J. Failure & Recovery Safety** | **WARNING** | PostgreSQL supports transactional DDL for standard structural operations, but non-transactional nuances (sequence increments, extension catalog hooks) mean failures cannot be blindly retried without investigation. |
| **K. Backup & Data Protection** | **PASS** | Neon Free plan provides **6-hour instant restore history**, **1 GB change history storage**, and **one manual snapshot**. Independent `pg_dump` procedure documented. |

---

## 3. Detailed V1–V7 Migration Inspection

Every Flyway script in [`src/main/resources/db/migration/`](file:///D:/railway-github-check/Railway-Booking-System/src/main/resources/db/migration) was inspected line-by-line:

```
src/main/resources/db/migration/
├── V1__initial_schema.sql                              (266 lines)
├── V2__add_segment_range_and_gist_exclusion.sql        (90 lines)
├── V3__add_tatkal_admission_to_seat_allocations.sql    (13 lines)
├── V4__add_pnr_to_bookings.sql                         (31 lines)
├── V5__create_outbox_events.sql                        (38 lines)
├── V6__add_outbox_claims_and_retry_schedule.sql        (9 lines)
└── V7__add_razorpay_payment_references.sql             (34 lines)
```

### Migration V1: Baseline Application Schema
- **File:** [`V1__initial_schema.sql`](file:///D:/railway-github-check/Railway-Booking-System/src/main/resources/db/migration/V1__initial_schema.sql)
- **Tables Created (14):** `users`, `stations`, `trains`, `coaches`, `seats`, `train_routes`, `train_stops`, `journeys`, `journey_stops`, `journey_seat_inventory`, `seat_allocations`, `bookings`, `payment_transactions`, `refresh_tokens`.
- **Identity Strategy:** Standard SQL:2003 / PostgreSQL `BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY`.
- **Foreign Key Hierarchy:**
  - `trains` <- `coaches` <- `seats`
  - `trains` <- `train_routes` <- `train_stops` -> `stations`
  - `trains`, `stations` <- `journeys` <- `journey_stops` -> `train_stops`
  - `journeys`, `coaches`, `seats`, `users` <- `journey_seat_inventory`
  - `journey_seat_inventory`, `journey_stops`, `users` <- `seat_allocations`
  - `users`, `journeys`, `coaches`, `seats`, `journey_seat_inventory`, `seat_allocations` <- `bookings`
  - `bookings` <- `payment_transactions`
  - `users` <- `refresh_tokens`
- **Data Types:** Standard PostgreSQL types (`BIGINT`, `VARCHAR`, `INT`, `NUMERIC(10,2)`, `DATE`, `TIME`, `TIMESTAMP`, `TIMESTAMPTZ`, `BOOLEAN`).
- **Safety Invariant:** All `CREATE TABLE` and `CREATE INDEX` statements follow dependency order. No cyclic dependencies exist.

### Migration V2: Segment Range & GiST Exclusion Constraint
- **File:** [`V2__add_segment_range_and_gist_exclusion.sql`](file:///D:/railway-github-check/Railway-Booking-System/src/main/resources/db/migration/V2__add_segment_range_and_gist_exclusion.sql)
- **Step 1 (Line 8):** `CREATE EXTENSION IF NOT EXISTS btree_gist;`
  - Required for GiST indexes combining scalar data types (`inventory_id BIGINT`) with range operators (`segment_range INT4RANGE`).
  - **Permission Caveat:** The existence of `btree_gist` in PostgreSQL or Neon's catalog does not guarantee that the assigned user role has permission to install it. While Neon documentation states `neondb_owner` possesses `neon_superuser` privileges, this **must be explicitly verified live** during the pre-migration step in Phase 17.4D.
- **Step 2 (Lines 11–50):** PL/pgSQL validation block (`DO $$ ... $$`).
  - Validates stop sequence integrity before schema mutation. On an empty database, `invalid_stops_count`, `invalid_journey_count`, and `invalid_sequence_count` evaluate to 0 and pass instantly.
- **Step 3–5 (Lines 53–70):** Adds `from_stop_seq` and `to_stop_seq` columns, backfills from stops, and adds `chk_seat_alloc_stop_seq CHECK (from_stop_seq >= 1 AND to_stop_seq > from_stop_seq)`.
- **Step 6 (Lines 73–76):**
  ```sql
  ALTER TABLE seat_allocations
      ADD COLUMN segment_range INT4RANGE GENERATED ALWAYS AS (
          int4range(from_stop_seq, to_stop_seq, '[)')
      ) STORED;
  ```
  - Creates a canonical half-open integer range `[from_stop_seq, to_stop_seq)`.
  - Supported natively in PostgreSQL 12+ (Neon runs PG 15+).
- **Step 7 (Lines 78–85):**
  ```sql
  ALTER TABLE seat_allocations
      ADD CONSTRAINT seat_alloc_no_overlap_excl EXCLUDE USING gist (
          inventory_id WITH =,
          segment_range WITH &&
      ) WHERE (status IN ('HOLD', 'BOOKED'));
  ```
  - Enforces authoritative mathematical double-booking prevention at the database engine level.
- **Step 8 (Lines 88–90):** Partial index `idx_seat_alloc_active_hold_expiry` for the background hold reaper.

### Migration V3: Tatkal Booking Mode & Admission Entry
- **File:** [`V3__add_tatkal_admission_to_seat_allocations.sql`](file:///D:/railway-github-check/Railway-Booking-System/src/main/resources/db/migration/V3__add_tatkal_admission_to_seat_allocations.sql)
- Adds `booking_mode VARCHAR(20) NOT NULL DEFAULT 'GENERAL'` and `admission_entry_id VARCHAR(36)`.
- Adds check constraints `chk_seat_allocation_booking_mode` and `chk_tatkal_allocation_has_admission`.
- Partial index: `idx_seat_allocation_admission_entry WHERE admission_entry_id IS NOT NULL`.

### Migration V4: 10-Digit Numeric PNR
- **File:** [`V4__add_pnr_to_bookings.sql`](file:///D:/railway-github-check/Railway-Booking-System/src/main/resources/db/migration/V4__add_pnr_to_bookings.sql)
- Adds `pnr VARCHAR(10)`, backfills deterministically via `ROW_NUMBER() OVER (ORDER BY booking_id ASC)`, enforces `NOT NULL` and `CONSTRAINT uk_bookings_pnr UNIQUE (pnr)`.
- On an empty database, the CTE backfill executes in ~0 ms without mutating any rows, then successfully establishes the unique constraint.

### Migration V5: Transactional Outbox & Idempotent Consumer Schema
- **File:** [`V5__create_outbox_events.sql`](file:///D:/railway-github-check/Railway-Booking-System/src/main/resources/db/migration/V5__create_outbox_events.sql)
- Creates `outbox_events` (`event_id VARCHAR(36) PRIMARY KEY`, `status`, `retry_count`, `created_at`, `payload TEXT`).
- Creates `processed_consumer_events` (`id BIGINT GENERATED BY DEFAULT AS IDENTITY`, `event_id`, `consumer_name`, `CONSTRAINT uk_processed_consumer_events UNIQUE (event_id, consumer_name)`).
- **Kafka Decoupling:** This migration creates purely relational tables. **Zero Kafka broker connectivity is required** to execute or migrate these tables.

### Migration V6: Outbox Polling Claims & Retry Schedule
- **File:** [`V6__add_outbox_claims_and_retry_schedule.sql`](file:///D:/railway-github-check/Railway-Booking-System/src/main/resources/db/migration/V6__add_outbox_claims_and_retry_schedule.sql)
- Adds `claim_token VARCHAR(36)`, `claimed_at TIMESTAMP`, and `next_attempt_at TIMESTAMP`.
- Creates composite index `idx_outbox_events_retry_schedule ON outbox_events (status, next_attempt_at, created_at)`.
- Works in tandem with runtime query `SELECT * FROM outbox_events ... FOR UPDATE SKIP LOCKED`.

### Migration V7: Razorpay Payment References & Refunds
- **File:** [`V7__add_razorpay_payment_references.sql`](file:///D:/railway-github-check/Railway-Booking-System/src/main/resources/db/migration/V7__add_razorpay_payment_references.sql)
- Adds nullable `gateway_order_id`, `gateway_order_receipt`, `gateway_payment_id` to `payment_transactions`.
- Creates unique constraints `uk_payment_gateway_order_id` and `uk_payment_gateway_payment_id`.
- Creates `razorpay_webhook_events` and `razorpay_refunds` tables.
- **Simulated Payment Invariant:** When Razorpay is disabled (`razorpay.enabled=false`) and simulated payment is active (`railway.payment.simulated-enabled=true`), these columns remain `NULL` and foreign keys operate seamlessly.

---

## 4. PostgreSQL & Neon Compatibility Assessment

### Direct Connection Invariant (Connection Pooling OFF)
- Neon offers two connection endpoints:
  1. **Pooled Connection:** Connects via PgBouncer on port 6543 / host with `-pooler`. In transaction mode, PgBouncer **breaks** Flyway's `pg_advisory_lock`, drops prepared statements across transactions, and disrupts row-level locks.
  2. **Direct Connection:** Connects straight to the PostgreSQL compute node on port 5432.
- **Audit Finding:** The target configuration uses **Direct Connection (pooling OFF)**.
- **Result:**
  - Flyway advisory locks (`pg_advisory_lock`) operate with 100% native PostgreSQL semantics.
  - Runtime row locks (`SELECT ... FOR UPDATE` and `FOR UPDATE SKIP LOCKED`) operate cleanly with full ACID guarantees.

### DDL Transactional Behavior & Important Qualifications
PostgreSQL supports transactional DDL for standard structural commands (`CREATE TABLE`, `ALTER TABLE`, `CREATE INDEX`, `ADD CONSTRAINT`). Each Flyway migration file is executed inside an atomic transaction block (`BEGIN ... COMMIT`).

**However, the assertion that every failed migration is automatically and completely rolled back must be qualified with technical realities:**
1. **Sequence Increments:** In PostgreSQL, sequence advances (`GENERATED BY DEFAULT AS IDENTITY`, `SERIAL`) are non-transactional. If a migration statement inserts data or triggers sequence increments before failing, sequence values are not restored upon transaction rollback. (On an empty database where no data is inserted, sequence counters remain at default).
2. **Catalog / Extension Side Effects:** `CREATE EXTENSION` modifies shared system catalogs (`pg_extension`). While statement failure aborts the transaction, if the extension binary fails to load or locks catalog objects, the connection session can be left in an aborted state requiring full connection recycling.
3. **Partial Failure & Flyway Tracking:** If a migration script fails, the DDL transaction aborts, but Flyway records a failed migration entry (`success = false`) in `flyway_schema_history`. **The database is not automatically safe to retry without investigation:**
   - The failure reason must be examined in the logs.
   - The schema must be verified to confirm no unexpected partial state exists.
   - `flyway repair` must be used deliberately (not blindly) to clear the failed migration entry.

---

## 5. Required Database Privileges & Permissions

The migrations require the following privileges on the target Neon database:
1. `CONNECT` and `TEMPORARY` on database `railway_booking_prod`.
2. `CREATE` on schema `public` (to create `flyway_schema_history` and application tables).
3. Privilege to execute `CREATE EXTENSION IF NOT EXISTS btree_gist;`:
   - In Neon, the default role `neondb_owner` is documented to possess `neon_superuser` privileges.
   - **Important Caveat:** Whether the specific project's `neondb_owner` role has been granted extension creation permissions without error **cannot be confirmed by an offline repository inspection**. This remains an item for controlled live verification.

---

## 6. Spring Boot Configuration & Startup Ordering

### Configuration Review
- **`application.properties`:**
  - `spring.jpa.hibernate.ddl-auto=validate`: Hibernate performs strict schema validation and never mutates DDL.
  - `spring.flyway.enabled=true`: Flyway manages 100% of schema DDL.
  - `spring.flyway.baseline-on-migrate=false`: Rejects accidental baseline creation on empty databases.
- **`application-production.properties`:**
  - Mandatory variables: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`.
  - Tuned connection lifetime: `spring.datasource.hikari.max-lifetime=${HIKARI_MAX_LIFETIME_MS:600000}` (10 minutes).
- **`FlywayConfig.java` Startup Ordering:**
  - Explicitly registers `EntityManagerFactoryDependsOnPostProcessor(Flyway.class)`.
  - Spring Framework guarantees:
    1. HikariCP DataSource initializes.
    2. Flyway bean initializes and executes `migrate()` (applying V1–V7).
    3. `EntityManagerFactory` initializes **only after** Flyway finishes.
    4. Hibernate validates the schema against the 17 mapped entities.

### Expected JDBC URL Format
```
jdbc:postgresql://<direct-neon-host>:5432/railway_booking_prod?sslmode=require
```
- **Driver:** PostgreSQL JDBC Driver 42.7.11 (`org.postgresql.Driver`).
- **Scheme:** `jdbc:postgresql://` (mandatory; standard `postgresql://` without `jdbc:` will fail).
- **SSL:** `sslmode=require` (mandatory for Neon).
- **Channel Binding:** Do **not** append `channel_binding=require`. The driver default (`channelBinding=prefer`) ensures robust TLS negotiation without risk of proxy-induced authentication drops.

---

## 7. Migration Execution Sequence

When the application starts in production mode against the empty Neon database, execution proceeds through the following deterministic sequence:

```
[Phase 1: Preflight Connection]
       │
       ▼
HikariCP establishes direct TLS connection to Neon (port 5432)
       │
       ▼
[Phase 2: Flyway Advisory Lock]
Flyway acquires session lock: pg_advisory_lock(...)
       │
       ▼
[Phase 3: Schema History Provisioning]
Creates table public.flyway_schema_history
       │
       ▼
[Phase 4: Sequential Migration Execution]
  ├── V1: Baseline Schema (14 tables, PKs, FKs, UKs, indexes)        [COMMITTED]
  ├── V2: btree_gist extension + INT4RANGE + GiST exclusion           [COMMITTED]
  ├── V3: Tatkal booking mode + admission checks + partial index       [COMMITTED]
  ├── V4: 10-digit numeric PNR + deterministic backfill + unique idx  [COMMITTED]
  ├── V5: Transactional outbox + idempotent consumer events           [COMMITTED]
  ├── V6: Outbox claim tokens + retry schedule composite index        [COMMITTED]
  └── V7: Razorpay references + refund & webhook tables               [COMMITTED]
       │
       ▼
[Phase 5: Flyway Lock Release]
Flyway records V1–V7 in flyway_schema_history and releases pg_advisory_unlock(...)
       │
       ▼
[Phase 6: Hibernate Schema Validation]
EntityManagerFactory initializes; validates all 17 JPA entities against PostgreSQL tables
       │
       ▼
[Phase 7: Application Readiness]
Spring Boot readiness probe (/readyz) reports STATUS: UP
```

---

## 8. Failure and Recovery Plan

### 1. Target Database Pre-Verification
- Before executing migrations, verify the target connection parameters:
  - Database name must match `railway_booking_prod`.
  - Host must resolve to the Neon AWS Singapore direct endpoint (`*.ap-southeast-1.aws.neon.tech`).
  - Host must **not** match `localhost`, `127.0.0.1`, or local Docker containers.
  - Pre-migration table count query (to be run via controlled connection):
    ```sql
    SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public';
    ```
    Expected: `0` (clean, unpolluted database).

### 2. Handling Partial Migration Failure
- If migration V1 succeeds but V2 fails (e.g. extension privilege issue):
  - In PostgreSQL, V2's DDL transaction aborts and rolls back.
  - V1 remains applied.
  - `flyway_schema_history` marks V2 as `success = false`.
- **Safe Recovery Procedure:**
  1. **Do not retry blindly:** Retrying without diagnosis will simply fail again.
  2. Inspect the application logs for the exact SQL error and error code.
  3. Resolve the underlying permission or configuration issue in Neon.
  4. Inspect the database state to ensure no dangling locks or sequence issues exist.
  5. Run `flyway repair` to remove the failed migration record from `flyway_schema_history`.
  6. Re-trigger application migration.

### 3. When `flyway repair` is Appropriate vs Dangerous
- **Appropriate:** After a migration failed under transactional DDL, the root cause was fixed, and the failed entry in `flyway_schema_history` needs to be cleared so Flyway can re-attempt the script; or when reconciling script checksums after harmless formatting changes.
- **Dangerous:** When executed on an out-of-sync database without diagnosing why the migration failed, or when applied after non-transactional statements have partially modified data or catalog state.
- **Rule:** Never drop or recreate the production database as a routine troubleshooting step.

### 4. Preventing Accidental Duplicate Execution
- Flyway's built-in session advisory locks (`pg_advisory_lock`) guarantee mutual exclusion on direct connections. Even if multiple Render web service replicas boot simultaneously, only one instance executes migrations while the others wait.

---

## 9. Backup and Data Safety

1. **Neon Free Plan Capabilities:**
   - **Instant Restore / PITR History:** Retains a **6-hour** point-in-time recovery window (not 24 hours).
   - **Change History Storage Cap:** Capped at **1 GB** of WAL/change history.
   - **Manual Snapshots:** Supports **one manual snapshot / branch** on the Free tier.
2. **Neon Zero-Copy Branching:**
   - The single supported manual snapshot/branch can be taken before major schema modifications as an instant rollback target.
3. **Independent Logical Backups (`pg_dump`):**
   - Because the Free plan PITR window is limited to 6 hours and 1 GB, an independent logical backup using `pg_dump` is strongly recommended before any future production schema modifications:
     ```bash
     pg_dump -h <neon-host> -U neondb_owner -d railway_booking_prod -F c -b -v -f railora_pre_migration.dump
     ```
4. **Credential Protection:**
   - Credentials must be stored exclusively in Render's encrypted Environment Variables dashboard.
   - Never commit `.env` or paste passwords into documentation or terminal logs.

---

## 10. Conditions for Proceeding to Migration (Phase 17.4D)

The migration is **READY WITH CONDITIONS** and may proceed once the operator satisfies the following precise conditions:

1. **Condition 1 — Target Host & Database Live Pre-Verification:**
   Establish a controlled read-only preflight check to confirm:
   - `SELECT current_database()` equals `railway_booking_prod`.
   - Host resolves to the Neon AWS Singapore endpoint and is not local.
   - `SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'` equals `0` (database is verified empty).
   - `flyway_schema_history` does not already exist.
2. **Condition 2 — Role Privilege Verification (`neondb_owner`):**
   Verify live that `neondb_owner` possesses sufficient privileges to create tables and execute `CREATE EXTENSION IF NOT EXISTS btree_gist;` without error.
3. **Condition 3 — JDBC Parameter Casing & Resilience:**
   Confirm the JDBC connection URL does not include `channel_binding=require`, relying on `sslmode=require` and driver default `channelBinding=prefer`.
4. **Condition 4 — Explicit Operator Authorization:**
   Explicit approval must be provided before executing Phase 17.4D.

---

## 11. Remaining Uncertainty Requiring Live Verification

The following items **cannot** be verified through offline repository analysis and must be confirmed during the live pre-check in Phase 17.4D:
1. **Live Database Emptiness:** Proving zero pre-existing tables or half-applied schemas exist in `railway_booking_prod`.
2. **Live Extension Privileges:** Proving `neondb_owner` has effective permission to execute `CREATE EXTENSION IF NOT EXISTS btree_gist;` in this specific Neon compute branch.
3. **Live Schema History Status:** Proving no prior `flyway_schema_history` table exists on the remote database.

---

## 12. Final Verification & Working-Tree Status

- **Safety Checks:**
  - Zero connections were established to Neon during this phase.
  - Zero SQL statements were executed against any database.
  - All migration SQL scripts (V1–V7) are completely unchanged.
  - All application configuration and Java source code are completely unchanged.
- **Git Working Tree Status:**
  ```text
   M Railway-Booking-System/src/main/resources/application-production.properties
  ?? Railway-Booking-System/docs/PHASE_17_4A_NEON_COMPATIBILITY_AUDIT.md
  ?? Railway-Booking-System/docs/PHASE_17_4B_CLOUD_CONFIGURATION_REMEDIATION.md
  ?? Railway-Booking-System/docs/PHASE_17_4C_FINAL_NEON_MIGRATION_PREFLIGHT.md
  ?? Railway-Booking-System/src/test/java/in/yograj/learnspringboot/railway_booking/config/ProductionPropertiesValidationTests.java
  ```
  *(Only Phase 17.4A/B/C documentation and validation tests exist in the working tree; no files have been committed or pushed.)*

---

**Preflight Audit & Corrections Complete.** Awaiting explicit operator approval before proceeding to Phase 17.4D.
