CREATE TABLE transfers
(
    id                UUID           NOT NULL,
    idempotency_key   VARCHAR(64)    NOT NULL,
    source_account_id UUID           NOT NULL,
    target_account_id UUID           NOT NULL,
    amount            NUMERIC(19, 2) NOT NULL,
    currency          VARCHAR(3)     NOT NULL,
    status            VARCHAR(20)    NOT NULL,
    failure_reason    VARCHAR(64),
    failure_message   VARCHAR(512),
    created_at        TIMESTAMPTZ    NOT NULL,
    updated_at        TIMESTAMPTZ    NOT NULL,
    completed_at      TIMESTAMPTZ,

    CONSTRAINT pk_transfers PRIMARY KEY (id),
    CONSTRAINT uq_transfers_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_transfers_source FOREIGN KEY (source_account_id) REFERENCES accounts (id),
    CONSTRAINT fk_transfers_target FOREIGN KEY (target_account_id) REFERENCES accounts (id),
    CONSTRAINT chk_transfers_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_transfers_different_accounts CHECK (source_account_id <> target_account_id),
    CONSTRAINT chk_transfers_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_transfers_source_created ON transfers (source_account_id, created_at DESC);
CREATE INDEX idx_transfers_target_created ON transfers (target_account_id, created_at DESC);
