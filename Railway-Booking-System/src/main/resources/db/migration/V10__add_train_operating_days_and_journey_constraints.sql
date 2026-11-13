-- ============================================================================
-- V10__add_train_operating_days_and_journey_constraints.sql
-- Railora Phase 10: Scale Reservation Engine to All Supported Trains
-- Adds operating days metadata, class profile support, and journey uniqueness
-- Dialect: PostgreSQL 12+ / H2 compatible
-- ============================================================================

-- Step 1: Add operating_days and class_profile to trains table
ALTER TABLE trains ADD COLUMN IF NOT EXISTS operating_days VARCHAR(60) DEFAULT 'DAILY';
ALTER TABLE trains ADD COLUMN IF NOT EXISTS class_profile VARCHAR(30) DEFAULT 'EXPRESS';

-- Step 2: Add operating_days_source to journeys table
ALTER TABLE journeys ADD COLUMN IF NOT EXISTS operating_days_source VARCHAR(50);

-- Step 3: Add unique constraint on journeys (train_id, journey_date) to prevent race-condition duplicates
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uk_journeys_train_date'
    ) THEN
        ALTER TABLE journeys ADD CONSTRAINT uk_journeys_train_date UNIQUE (train_id, journey_date);
    END IF;
EXCEPTION
    WHEN duplicate_table OR duplicate_object THEN
        -- constraint already exists
        NULL;
END $$;

-- Step 4: Add index on trains for fast case-insensitive name and number search
CREATE INDEX IF NOT EXISTS idx_trains_number_name ON trains (train_number, train_name);
