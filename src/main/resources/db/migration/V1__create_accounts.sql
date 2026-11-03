CREATE TABLE accounts
(
    id         UUID           NOT NULL,
    owner_name VARCHAR(120)   NOT NULL,
    email      VARCHAR(320)   NOT NULL,
    status     VARCHAR(20)    NOT NULL,
    balance    NUMERIC(19, 2) NOT NULL DEFAULT 0,
    currency   VARCHAR(3)     NOT NULL,
    created_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT pk_accounts PRIMARY KEY (id),
    CONSTRAINT uq_accounts_email UNIQUE (email),
    CONSTRAINT chk_accounts_balance_non_negative CHECK (balance >= 0),
    CONSTRAINT chk_accounts_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_accounts_status CHECK (status IN ('ACTIVE', 'BLOCKED'))
);

CREATE INDEX idx_accounts_email_lower ON accounts (lower(email));
