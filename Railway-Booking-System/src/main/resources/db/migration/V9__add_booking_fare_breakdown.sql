-- ============================================================================
-- V9__add_booking_fare_breakdown.sql
-- Railora Phase 17.19: Dynamic Fare Calculation Engine & Fare Breakdown Snapshot
-- Resequenced from development V4 to canonical V9
-- Dialect: PostgreSQL 12+ / H2 compatible
-- ============================================================================

-- Step 1: Add fare breakdown snapshot and distance columns to bookings table
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS fare_breakdown_json TEXT;
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS distance_km INT;

-- Step 2: Add distance metric to train_stops and journey_stops for distance-based calculations
ALTER TABLE train_stops ADD COLUMN IF NOT EXISTS distance_from_origin_km NUMERIC(8, 2);
ALTER TABLE journey_stops ADD COLUMN IF NOT EXISTS distance_from_origin_km NUMERIC(8, 2);
