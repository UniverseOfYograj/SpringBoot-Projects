ALTER TABLE seat_allocations
    ADD COLUMN booking_mode VARCHAR(20) NOT NULL DEFAULT 'GENERAL',
    ADD COLUMN admission_entry_id VARCHAR(36);

ALTER TABLE seat_allocations
    ADD CONSTRAINT chk_seat_allocation_booking_mode
        CHECK (booking_mode IN ('GENERAL', 'TATKAL')),
    ADD CONSTRAINT chk_tatkal_allocation_has_admission
        CHECK (booking_mode <> 'TATKAL' OR admission_entry_id IS NOT NULL);

CREATE INDEX idx_seat_allocation_admission_entry
    ON seat_allocations (admission_entry_id)
    WHERE admission_entry_id IS NOT NULL;
