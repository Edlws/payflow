-- Demo data. Remove this migration if you do not need seeded accounts.
INSERT INTO accounts (id, owner_name, email, status, balance, currency, created_at, updated_at)
VALUES ('11111111-1111-1111-1111-111111111111', 'Alice Johnson', 'alice@example.com', 'ACTIVE', 1000.00, 'USD', now(), now()),
       ('22222222-2222-2222-2222-222222222222', 'Bob Smith', 'bob@example.com', 'ACTIVE', 250.00, 'USD', now(), now()),
       ('33333333-3333-3333-3333-333333333333', 'Carol Danvers', 'carol@example.com', 'BLOCKED', 500.00, 'USD', now(), now()),
       ('44444444-4444-4444-4444-444444444444', 'Eve Adams', 'eve@example.com', 'ACTIVE', 750.00, 'EUR', now(), now());
