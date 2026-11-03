-- Durable publisher claims allow Kafka I/O to happen outside database transactions
-- while preventing multiple application instances from processing the same event.
ALTER TABLE outbox_events
    ADD COLUMN claim_token VARCHAR(36),
    ADD COLUMN claimed_at TIMESTAMP,
    ADD COLUMN next_attempt_at TIMESTAMP;

CREATE INDEX idx_outbox_events_retry_schedule
    ON outbox_events (status, next_attempt_at, created_at);
