-- Idempotency keys identify client requests; an external settlement has its own durable identity.
-- Prefix references with the provider name, e.g. bank-a:settlement-1234.
CREATE UNIQUE INDEX unique_funding_reference ON transfers(description) WHERE type = 'FUNDING';
