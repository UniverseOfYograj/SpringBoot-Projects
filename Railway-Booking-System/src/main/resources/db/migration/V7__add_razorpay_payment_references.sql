ALTER TABLE payment_transactions
    ADD COLUMN gateway_order_id VARCHAR(64),
    ADD COLUMN gateway_order_receipt VARCHAR(40),
    ADD COLUMN gateway_payment_id VARCHAR(64);

ALTER TABLE payment_transactions
    ADD CONSTRAINT uk_payment_gateway_order_id UNIQUE (gateway_order_id);

ALTER TABLE payment_transactions
    ADD CONSTRAINT uk_payment_gateway_payment_id UNIQUE (gateway_payment_id);

CREATE TABLE razorpay_webhook_events (
    event_id VARCHAR(100) PRIMARY KEY,
    event_type VARCHAR(80) NOT NULL,
    gateway_entity_id VARCHAR(64),
    processed_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_razorpay_webhook_processed_at
    ON razorpay_webhook_events (processed_at);

CREATE TABLE razorpay_refunds (
    gateway_refund_id VARCHAR(64) PRIMARY KEY,
    payment_id BIGINT NOT NULL,
    amount NUMERIC(10, 2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_razorpay_refunds_payment
        FOREIGN KEY (payment_id) REFERENCES payment_transactions (payment_id)
);

CREATE INDEX idx_razorpay_refunds_payment_id
    ON razorpay_refunds (payment_id);
