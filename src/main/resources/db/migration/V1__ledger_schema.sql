CREATE TABLE users (
    id uuid PRIMARY KEY,
    email varchar(254) NOT NULL UNIQUE,
    full_name varchar(100) NOT NULL,
    password_hash text NOT NULL,
    role varchar(10) NOT NULL CHECK (role IN ('USER', 'ADMIN')),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE sessions (
    token_hash varchar(64) PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id),
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX sessions_expiry_idx ON sessions(expires_at);
CREATE INDEX sessions_user_idx ON sessions(user_id);

CREATE TABLE auth_rate_limits (
    bucket_key varchar(64) PRIMARY KEY,
    window_start timestamptz NOT NULL,
    attempts integer NOT NULL CHECK (attempts > 0)
);
CREATE INDEX auth_rate_limits_window_idx ON auth_rate_limits(window_start);

CREATE TABLE accounts (
    id uuid PRIMARY KEY,
    owner_id uuid REFERENCES users(id),
    label varchar(60) NOT NULL,
    currency varchar(3) NOT NULL CHECK (currency IN ('TRY', 'USD', 'EUR')),
    kind varchar(6) NOT NULL CHECK (kind IN ('USER', 'SYSTEM')),
    balance numeric(19,2) NOT NULL DEFAULT 0,
    status varchar(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT account_owner_kind CHECK ((kind = 'USER' AND owner_id IS NOT NULL) OR (kind = 'SYSTEM' AND owner_id IS NULL)),
    CONSTRAINT nonnegative_user_balance CHECK (kind = 'SYSTEM' OR balance >= 0),
    CONSTRAINT closed_account_empty CHECK (status <> 'CLOSED' OR balance = 0)
);
CREATE INDEX accounts_owner_idx ON accounts(owner_id, created_at DESC, id);
CREATE UNIQUE INDEX one_treasury_per_currency ON accounts(currency) WHERE kind = 'SYSTEM';

CREATE TABLE transfers (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL REFERENCES users(id),
    source_account_id uuid NOT NULL REFERENCES accounts(id),
    destination_account_id uuid NOT NULL REFERENCES accounts(id),
    amount numeric(19,2) NOT NULL CHECK (amount > 0 AND amount <= 999999999999.99),
    currency varchar(3) NOT NULL CHECK (currency IN ('TRY', 'USD', 'EUR')),
    description varchar(200) NOT NULL,
    type varchar(8) NOT NULL CHECK (type IN ('TRANSFER', 'FUNDING', 'REVERSAL')),
    reversal_of uuid UNIQUE REFERENCES transfers(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (source_account_id <> destination_account_id),
    CHECK ((type = 'REVERSAL') = (reversal_of IS NOT NULL)),
    CHECK (id IS DISTINCT FROM reversal_of)
);
CREATE INDEX transfers_source_history_idx ON transfers(source_account_id, created_at DESC, id DESC);
CREATE INDEX transfers_destination_history_idx ON transfers(destination_account_id, created_at DESC, id DESC);

CREATE TABLE ledger_entries (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id uuid NOT NULL REFERENCES transfers(id),
    account_id uuid NOT NULL REFERENCES accounts(id),
    delta numeric(19,2) NOT NULL CHECK (delta <> 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (transfer_id, account_id)
);
CREATE INDEX ledger_entries_account_idx ON ledger_entries(account_id);

CREATE TABLE idempotency_keys (
    actor_id uuid NOT NULL REFERENCES users(id),
    key varchar(128) NOT NULL,
    request_hash varchar(64) NOT NULL,
    transfer_id uuid NOT NULL UNIQUE REFERENCES transfers(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (actor_id, key)
);

CREATE TABLE audit_events (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id uuid NOT NULL REFERENCES users(id),
    action varchar(40) NOT NULL,
    entity_id uuid NOT NULL,
    details varchar(500) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_events_actor_idx ON audit_events(actor_id, created_at DESC);

-- Balances start at zero; funding is a balanced movement out of a system treasury.
INSERT INTO accounts (id, label, currency, kind) VALUES
    ('00000000-0000-0000-0000-000000000001', 'Treasury TRY', 'TRY', 'SYSTEM'),
    ('00000000-0000-0000-0000-000000000002', 'Treasury USD', 'USD', 'SYSTEM'),
    ('00000000-0000-0000-0000-000000000003', 'Treasury EUR', 'EUR', 'SYSTEM');

CREATE FUNCTION deny_immutable_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Financial history is append-only: %', TG_TABLE_NAME USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER immutable_transfers BEFORE UPDATE OR DELETE ON transfers FOR EACH ROW EXECUTE FUNCTION deny_immutable_mutation();
CREATE TRIGGER immutable_ledger BEFORE UPDATE OR DELETE ON ledger_entries FOR EACH ROW EXECUTE FUNCTION deny_immutable_mutation();
CREATE TRIGGER immutable_idempotency BEFORE UPDATE OR DELETE ON idempotency_keys FOR EACH ROW EXECUTE FUNCTION deny_immutable_mutation();
CREATE TRIGGER immutable_audit BEFORE UPDATE OR DELETE ON audit_events FOR EACH ROW EXECUTE FUNCTION deny_immutable_mutation();
CREATE TRIGGER no_truncate_transfers BEFORE TRUNCATE ON transfers FOR EACH STATEMENT EXECUTE FUNCTION deny_immutable_mutation();
CREATE TRIGGER no_truncate_ledger BEFORE TRUNCATE ON ledger_entries FOR EACH STATEMENT EXECUTE FUNCTION deny_immutable_mutation();
CREATE TRIGGER no_truncate_idempotency BEFORE TRUNCATE ON idempotency_keys FOR EACH STATEMENT EXECUTE FUNCTION deny_immutable_mutation();
CREATE TRIGGER no_truncate_audit BEFORE TRUNCATE ON audit_events FOR EACH STATEMENT EXECUTE FUNCTION deny_immutable_mutation();

CREATE FUNCTION protect_account_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.owner_id IS DISTINCT FROM OLD.owner_id
       OR NEW.currency IS DISTINCT FROM OLD.currency OR NEW.kind IS DISTINCT FROM OLD.kind
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Account identity cannot change' USING ERRCODE = '23514';
    END IF;
    IF OLD.status = 'CLOSED' AND NEW.status <> 'CLOSED' THEN
        RAISE EXCEPTION 'Closed accounts cannot reopen' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_identity BEFORE UPDATE ON accounts FOR EACH ROW EXECUTE FUNCTION protect_account_identity();

CREATE FUNCTION verify_transfer(p_id uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    t transfers%ROWTYPE;
    original transfers%ROWTYPE;
    source accounts%ROWTYPE;
    destination accounts%ROWTYPE;
    leg_count integer;
    valid_legs integer;
BEGIN
    SELECT * INTO STRICT t FROM transfers WHERE id = p_id;
    SELECT * INTO STRICT source FROM accounts WHERE id = t.source_account_id;
    SELECT * INTO STRICT destination FROM accounts WHERE id = t.destination_account_id;
    SELECT count(*), count(*) FILTER (WHERE
        (account_id = t.source_account_id AND delta = -t.amount) OR
        (account_id = t.destination_account_id AND delta = t.amount))
      INTO leg_count, valid_legs FROM ledger_entries WHERE transfer_id = p_id;
    IF leg_count <> 2 OR valid_legs <> 2 OR source.currency <> t.currency OR destination.currency <> t.currency THEN
        RAISE EXCEPTION 'Transfer must have two matching balanced journal entries in one currency' USING ERRCODE = '23514';
    END IF;
    IF (t.type = 'TRANSFER' AND (source.kind <> 'USER' OR destination.kind <> 'USER'))
       OR (t.type = 'FUNDING' AND (source.kind <> 'SYSTEM' OR destination.kind <> 'USER')) THEN
        RAISE EXCEPTION 'Invalid account kinds for movement' USING ERRCODE = '23514';
    END IF;
    IF t.type = 'REVERSAL' THEN
        SELECT * INTO STRICT original FROM transfers WHERE id = t.reversal_of;
        IF original.type = 'REVERSAL' OR original.source_account_id <> t.destination_account_id
           OR original.destination_account_id <> t.source_account_id OR original.amount <> t.amount
           OR original.currency <> t.currency THEN
            RAISE EXCEPTION 'Reversal must exactly compensate its original transfer' USING ERRCODE = '23514';
        END IF;
    END IF;
END;
$$;

CREATE FUNCTION check_transfer_trigger() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'transfers' THEN
        PERFORM verify_transfer(NEW.id);
    ELSE
        PERFORM verify_transfer(NEW.transfer_id);
    END IF;
    RETURN NULL;
END;
$$;
-- Deferred checks run at COMMIT, after both journal legs and both balances exist.
CREATE CONSTRAINT TRIGGER balanced_transfer AFTER INSERT ON transfers DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_transfer_trigger();
CREATE CONSTRAINT TRIGGER balanced_ledger AFTER INSERT ON ledger_entries DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_transfer_trigger();

CREATE FUNCTION check_account_balance_trigger() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    p_id uuid;
    stored_balance numeric;
    journal_balance numeric;
BEGIN
    IF TG_TABLE_NAME = 'accounts' THEN p_id := NEW.id; ELSE p_id := NEW.account_id; END IF;
    SELECT balance INTO STRICT stored_balance FROM accounts WHERE id = p_id;
    SELECT coalesce(sum(delta), 0) INTO journal_balance FROM ledger_entries WHERE account_id = p_id;
    IF stored_balance <> journal_balance THEN
        RAISE EXCEPTION 'Account balance must equal its journal: %', p_id USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER account_balance_consistent AFTER INSERT OR UPDATE ON accounts DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_account_balance_trigger();
CREATE CONSTRAINT TRIGGER journal_balance_consistent AFTER INSERT ON ledger_entries DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_account_balance_trigger();
