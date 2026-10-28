-- ============================================================================
-- V4__add_pnr_to_bookings.sql
-- Railora: Standard 10-digit numeric PNR for bookings
-- Dialect: PostgreSQL 12+
-- ============================================================================

-- Step 1: Add column pnr as VARCHAR(10) (initially nullable for backfill)
ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS pnr VARCHAR(10);

-- Step 2: Backfill existing records with unique, deterministic 10-digit numeric PNRs.
-- Uses ROW_NUMBER() OVER (ORDER BY booking_id ASC) to guarantee strict uniqueness across all rows,
-- avoiding modulo wrap-around or remainder collisions regardless of booking_id magnitude or sequence offsets.
WITH numbered_bookings AS (
    SELECT booking_id,
           ROW_NUMBER() OVER (ORDER BY booking_id ASC) AS row_num
    FROM bookings
    WHERE pnr IS NULL
)
UPDATE bookings b
SET pnr = LPAD(nb.row_num::text, 10, '0')
FROM numbered_bookings nb
WHERE b.booking_id = nb.booking_id;

-- Step 3: Enforce NOT NULL constraint
ALTER TABLE bookings
    ALTER COLUMN pnr SET NOT NULL;

-- Step 4: Enforce UNIQUE constraint across all bookings (automatically creates backing index)
ALTER TABLE bookings
    ADD CONSTRAINT uk_bookings_pnr UNIQUE (pnr);
