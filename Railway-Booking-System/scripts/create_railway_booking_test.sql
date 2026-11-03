-- ============================================================================
-- Railora Railway Booking System: Dedicated Isolated Test Database Creation
-- File: scripts/create_railway_booking_test.sql
-- Target Database: railway_booking_test ONLY
--
-- SAFETY INVARIANTS:
-- 1. This script MUST NEVER target, drop, reset, or modify 'railway_booking'.
-- 2. Database creation is strictly idempotent and non-destructive.
-- 3. Schema migration (V1-V5) and test fixtures are kept decoupled from creation.
-- 4. Requires a PostgreSQL role possessing CREATEDB and extension privileges:
--      psql -h localhost -p 5432 -U postgres -d postgres -f scripts/create_railway_booking_test.sql
-- ============================================================================

-- Safety Gate 1: Ensure the script is NOT being executed against development database
DO $$
BEGIN
    IF current_database() = 'railway_booking' THEN
        RAISE EXCEPTION 'CRITICAL SAFETY ABORT: Execution detected on development database "railway_booking". Halting immediately.';
    END IF;
END $$;

-- Safety Gate 2: Idempotent database creation for railway_booking_test
-- In PostgreSQL, CREATE DATABASE cannot run inside a multi-statement transaction block.
-- The query below creates railway_booking_test only if it does not already exist.
SELECT 'CREATE DATABASE railway_booking_test'
WHERE NOT EXISTS (
    SELECT 1 FROM pg_database WHERE datname = 'railway_booking_test'
)\gexec

-- Step 3: Switch connection context to the isolated test database
\connect railway_booking_test

-- Safety Gate 3: Re-verify connection context is strictly on railway_booking_test
DO $$
BEGIN
    IF current_database() <> 'railway_booking_test' THEN
        RAISE EXCEPTION 'CRITICAL SAFETY ABORT: Active database is NOT "railway_booking_test" (current: "%"). Halting immediately.', current_database();
    END IF;
END $$;

-- Step 4: Pre-provision the required btree_gist extension for Migration V2
-- Flyway migration V2 requires btree_gist for int4range exclusion constraints.
-- Pre-enabling it ensures extension creation succeeds under database owner privileges.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- Verify btree_gist extension presence
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'btree_gist') THEN
        RAISE EXCEPTION 'VERIFICATION FAILED: Required extension "btree_gist" could not be enabled in railway_booking_test.';
    END IF;
    RAISE NOTICE 'SUCCESS: railway_booking_test database and btree_gist extension verified successfully.';
END $$;
