-- ============================================================================
-- V2__add_segment_range_and_gist_exclusion.sql
-- Railora Phase 1: Authoritative Route-Segment Inventory Invariant & GiST Exclusion
-- Dialect: PostgreSQL 12+
-- ============================================================================

-- Step 1: Enable btree_gist extension for combining scalar inventory_id with range operator
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- Step 2: Validate existing seat_allocations data integrity BEFORE backfilling or schema changes
DO $$
DECLARE
    invalid_stops_count INT;
    invalid_journey_count INT;
    invalid_sequence_count INT;
BEGIN
    -- Check 1: Ensure boarding and alighting stop references exist
    SELECT COUNT(*) INTO invalid_stops_count
    FROM seat_allocations a
    LEFT JOIN journey_stops bs ON a.boarding_journey_stop_id = bs.journey_stop_id
    LEFT JOIN journey_stops als ON a.alighting_journey_stop_id = als.journey_stop_id
    WHERE bs.journey_stop_id IS NULL OR als.journey_stop_id IS NULL;

    IF invalid_stops_count > 0 THEN
        RAISE EXCEPTION 'Migration V2 aborted: % seat_allocation record(s) reference non-existent boarding or alighting stops', invalid_stops_count;
    END IF;

    -- Check 2: Ensure stops belong to the exact same journey as the inventory record
    SELECT COUNT(*) INTO invalid_journey_count
    FROM seat_allocations a
    JOIN journey_seat_inventory jsi ON a.inventory_id = jsi.inventory_id
    JOIN journey_stops bs ON a.boarding_journey_stop_id = bs.journey_stop_id
    JOIN journey_stops als ON a.alighting_journey_stop_id = als.journey_stop_id
    WHERE bs.journey_id <> jsi.journey_id OR als.journey_id <> jsi.journey_id;

    IF invalid_journey_count > 0 THEN
        RAISE EXCEPTION 'Migration V2 aborted: % seat_allocation record(s) reference stops belonging to a different journey than their inventory', invalid_journey_count;
    END IF;

    -- Check 3: Ensure boarding stop sequence is strictly less than alighting stop sequence
    SELECT COUNT(*) INTO invalid_sequence_count
    FROM seat_allocations a
    JOIN journey_stops bs ON a.boarding_journey_stop_id = bs.journey_stop_id
    JOIN journey_stops als ON a.alighting_journey_stop_id = als.journey_stop_id
    WHERE bs.stop_sequence >= als.stop_sequence;

    IF invalid_sequence_count > 0 THEN
        RAISE EXCEPTION 'Migration V2 aborted: % seat_allocation record(s) have boarding stop_sequence >= alighting stop_sequence', invalid_sequence_count;
    END IF;
END $$;

-- Step 3: Add sequence columns to seat_allocations
ALTER TABLE seat_allocations
    ADD COLUMN IF NOT EXISTS from_stop_seq INT,
    ADD COLUMN IF NOT EXISTS to_stop_seq INT;

-- Step 4: Backfill from_stop_seq and to_stop_seq from journey_stops
UPDATE seat_allocations a
SET from_stop_seq = bs.stop_sequence,
    to_stop_seq = als.stop_sequence
FROM journey_stops bs, journey_stops als
WHERE a.boarding_journey_stop_id = bs.journey_stop_id
  AND a.alighting_journey_stop_id = als.journey_stop_id
  AND (a.from_stop_seq IS NULL OR a.to_stop_seq IS NULL);

-- Step 5: Enforce NOT NULL and positive sequence ordering
ALTER TABLE seat_allocations
    ALTER COLUMN from_stop_seq SET NOT NULL,
    ALTER COLUMN to_stop_seq SET NOT NULL,
    ADD CONSTRAINT chk_seat_alloc_stop_seq CHECK (from_stop_seq >= 1 AND to_stop_seq > from_stop_seq);

-- Step 6: Add generated canonical half-open integer range column [from_stop_seq, to_stop_seq)
ALTER TABLE seat_allocations
    ADD COLUMN segment_range INT4RANGE GENERATED ALWAYS AS (
        int4range(from_stop_seq, to_stop_seq, '[)')
    ) STORED;

-- Step 7: Authoritative GiST Exclusion Constraint
-- Enforces that no two active allocations (HOLD or BOOKED) for the same physical berth (inventory_id)
-- on the same train journey can ever share an overlapping station segment.
ALTER TABLE seat_allocations
    ADD CONSTRAINT seat_alloc_no_overlap_excl EXCLUDE USING gist (
        inventory_id WITH =,
        segment_range WITH &&
    ) WHERE (status IN ('HOLD', 'BOOKED'));

-- Step 8: Partial index for active hold expiry reaper scans
CREATE INDEX IF NOT EXISTS idx_seat_alloc_active_hold_expiry
    ON seat_allocations (hold_expires_at)
    WHERE status = 'HOLD';
