-- Migration 003: Add uniqueness constraint for (booking_ref, idempotency_key)
-- Guarantees at the database level that no duplicate payment transaction can be created for the same booking and idempotency key.
-- In standard SQL and PostgreSQL, multiple rows with NULL idempotency_key are permitted and do not conflict.

ALTER TABLE payment_transactions
    ADD CONSTRAINT uk_payment_booking_idempotency UNIQUE (booking_ref, idempotency_key);
