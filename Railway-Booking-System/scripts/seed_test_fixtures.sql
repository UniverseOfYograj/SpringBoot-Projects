-- ============================================================================
-- Railora Railway Booking System: Optional SQL Test Fixtures for railway_booking_test
-- File: scripts/seed_test_fixtures.sql
-- Target Database: railway_booking_test ONLY
--
-- Note: Prefer the application's native startup seed (SEED_JOURNEY_DATA_ENABLED=true)
-- and public registration endpoint (POST /api/auth/register) to avoid manual SQL seeding.
-- If applying manually via psql, ensure Flyway migrations V1-V5 have already executed.
-- ============================================================================

-- Safety Gate: Ensure we are connected strictly to railway_booking_test
DO $$
BEGIN
    IF current_database() <> 'railway_booking_test' THEN
        RAISE EXCEPTION 'CRITICAL SAFETY ABORT: Attempting to seed on database "%" (must be "railway_booking_test"). Halting.', current_database();
    END IF;
    IF current_database() = 'railway_booking' THEN
        RAISE EXCEPTION 'CRITICAL SAFETY ABORT: Execution detected on development database "railway_booking". Halting.';
    END IF;
END $$;

-- 1. Reference Stations
INSERT INTO stations (station_code, station_name, city, state)
VALUES ('NDLS', 'New Delhi', 'New Delhi', 'Delhi'),
       ('BCT', 'Mumbai Central', 'Mumbai', 'Maharashtra')
ON CONFLICT (station_code) DO NOTHING;

-- 2. Train Reference
INSERT INTO trains (train_number, train_name, status)
VALUES ('12190', 'August Kranti Rajdhani Express', 'ACTIVE')
ON CONFLICT (train_number) DO NOTHING;

-- 3. Coach Reference (Coach B1, Capacity 4)
INSERT INTO coaches (train_id, coach_number, coach_type, seat_capacity, status)
SELECT t.train_id, 'B1', 'SECOND_SEATING', 4, 'ACTIVE'
FROM trains t
WHERE t.train_number = '12190'
  AND NOT EXISTS (
      SELECT 1 FROM coaches c WHERE c.train_id = t.train_id AND c.coach_number = 'B1'
  );

-- 4. Physical Seats (Seats 1 through 4)
INSERT INTO seats (coach_id, seat_number, seat_type, status)
SELECT c.coach_id, s.seat_num, 'SECOND_SEATING', 'AVAILABLE'
FROM coaches c
JOIN trains t ON c.train_id = t.train_id
CROSS JOIN (VALUES (1), (2), (3), (4)) AS s(seat_num)
WHERE t.train_number = '12190' AND c.coach_number = 'B1'
  AND NOT EXISTS (
      SELECT 1 FROM seats st WHERE st.coach_id = c.coach_id AND st.seat_number = s.seat_num
  );

-- 5. Dated Journey (Current Date + 2 Days)
INSERT INTO journeys (train_id, from_station_id, to_station_id, journey_date, departure_time, arrival_time)
SELECT t.train_id, s1.station_id, s2.station_id, CURRENT_DATE + INTERVAL '2 days', '20:30:00', '08:45:00'
FROM trains t
JOIN stations s1 ON s1.station_code = 'NDLS'
JOIN stations s2 ON s2.station_code = 'BCT'
WHERE t.train_number = '12190'
  AND NOT EXISTS (
      SELECT 1 FROM journeys j
      WHERE j.train_id = t.train_id
        AND j.from_station_id = s1.station_id
        AND j.to_station_id = s2.station_id
        AND j.journey_date = CURRENT_DATE + INTERVAL '2 days'
  );

-- 6. Dated Journey Stops (Stop 1: NDLS, Stop 2: BCT)
INSERT INTO journey_stops (journey_id, station_id, stop_sequence, departure_at, arrival_at)
SELECT j.journey_id, s1.station_id, 1,
       (j.journey_date + j.departure_time::time),
       NULL
FROM journeys j
JOIN stations s1 ON s1.station_code = 'NDLS'
JOIN trains t ON j.train_id = t.train_id
WHERE t.train_number = '12190' AND j.journey_date = CURRENT_DATE + INTERVAL '2 days'
  AND NOT EXISTS (
      SELECT 1 FROM journey_stops js WHERE js.journey_id = j.journey_id AND js.stop_sequence = 1
  );

INSERT INTO journey_stops (journey_id, station_id, stop_sequence, departure_at, arrival_at)
SELECT j.journey_id, s2.station_id, 2,
       NULL,
       (j.journey_date + INTERVAL '1 day' + j.arrival_time::time)
FROM journeys j
JOIN stations s2 ON s2.station_code = 'BCT'
JOIN trains t ON j.train_id = t.train_id
WHERE t.train_number = '12190' AND j.journey_date = CURRENT_DATE + INTERVAL '2 days'
  AND NOT EXISTS (
      SELECT 1 FROM journey_stops js WHERE js.journey_id = j.journey_id AND js.stop_sequence = 2
  );

-- 7. Journey Seat Inventory
INSERT INTO journey_seat_inventory (journey_id, coach_id, seat_id, status, updated_at)
SELECT j.journey_id, c.coach_id, st.seat_id, 'AVAILABLE', CURRENT_TIMESTAMP
FROM journeys j
JOIN trains t ON j.train_id = t.train_id
JOIN coaches c ON c.train_id = t.train_id AND c.coach_number = 'B1'
JOIN seats st ON st.coach_id = c.coach_id
WHERE t.train_number = '12190' AND j.journey_date = CURRENT_DATE + INTERVAL '2 days'
  AND NOT EXISTS (
      SELECT 1 FROM journey_seat_inventory jsi
      WHERE jsi.journey_id = j.journey_id AND jsi.seat_id = st.seat_id
  );

-- Verification Notice
DO $$
DECLARE
    journey_count INT;
    inventory_count INT;
BEGIN
    SELECT count(*) INTO journey_count FROM journeys WHERE journey_date = CURRENT_DATE + INTERVAL '2 days';
    SELECT count(*) INTO inventory_count FROM journey_seat_inventory;
    RAISE NOTICE 'Fixtures verified: % journey(s), % inventory record(s) ready in railway_booking_test.', journey_count, inventory_count;
END $$;
