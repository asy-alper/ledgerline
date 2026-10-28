package dev.ledgerline.ledger;

import dev.ledgerline.api.ApiException;
import dev.ledgerline.auth.AuthService.User;
import dev.ledgerline.db.Database;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Transaction boundaries live here so HTTP and a future Spring adapter share the same rules. */
public final class LedgerService {
    private static final BigDecimal MAX_BALANCE = new BigDecimal("99999999999999999.99");
    private static final Map<String, UUID> TREASURIES = Map.of(
            "TRY", UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "USD", UUID.fromString("00000000-0000-0000-0000-000000000002"),
            "EUR", UUID.fromString("00000000-0000-0000-0000-000000000003"));
    private static final String TRANSFER_SELECT = """
            SELECT t.*, CASE WHEN EXISTS (SELECT 1 FROM transfers r WHERE r.reversal_of = t.id)
              THEN 'REVERSED' ELSE 'COMPLETED' END AS status FROM transfers t
            """;
    private final Database db;

    public LedgerService(Database db) {
        this.db = Objects.requireNonNull(db);
    }

    public record Account(UUID id, UUID ownerId, String label, String currency, BigDecimal balance,
                          String status, Instant createdAt) {}
    public record Transfer(UUID id, UUID sourceAccountId, UUID destinationAccountId, BigDecimal amount,
                           String currency, String description, String status, UUID reversalOf,
                           Instant createdAt) {}
    public record Mutation(Transfer transfer, boolean replayed) {}
    public record Page<T>(List<T> items, int limit, int offset) {}
    public record BalanceMismatch(UUID accountId, BigDecimal balance, BigDecimal ledgerBalance) {}
    public record Reconciliation(boolean balanced, long checkedAccounts, long checkedTransfers,
                                 List<BalanceMismatch> mismatches, long invalidTransfers) {}

    public Account createAccount(User user, String label, String currency) {
        String validLabel = text(label, "label", 1, 60);
        String validCurrency = currency == null ? "" : currency.strip().toUpperCase(Locale.ROOT);
        if (!TREASURIES.containsKey(validCurrency)) {
            throw validation("currency must be TRY, USD or EUR");
        }
        return db.transaction(c -> {
            UUID id = UUID.randomUUID();
            try (PreparedStatement s = c.prepareStatement("""
                    INSERT INTO accounts(id, owner_id, label, currency, kind)
                    VALUES (?, ?, ?, ?, 'USER') RETURNING *
                    """)) {
                s.setObject(1, id);
                s.setObject(2, user.id());
                s.setString(3, validLabel);
                s.setString(4, validCurrency);
                try (ResultSet r = s.executeQuery()) {
                    r.next();
                    Account account = account(r);
                    audit(c, user, "ACCOUNT_CREATED", id, validCurrency);
                    return account;
                }
            }
        });
    }

    public List<Account> listAccounts(User user) {
        return db.read(c -> {
            List<Account> result = new ArrayList<>();
            try (PreparedStatement s = c.prepareStatement("SELECT * FROM accounts WHERE owner_id = ? ORDER BY created_at DESC, id")) {
                s.setObject(1, user.id());
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) result.add(account(r));
                }
            }
            return List.copyOf(result);
        });
    }

    public Account getAccount(User user, UUID id) {
        return db.read(c -> {
            Account result = findAccount(c, id, false);
            requireAccess(user, result);
            return result;
        });
    }

    public Mutation transfer(User user, String key, UUID from, UUID to, String amount, String description) {
        String validKey = idempotencyKey(key);
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.equals(to)) throw validation("source and destination must be different accounts");
        BigDecimal money = money(amount);
        String memo = optionalText(description, "description", 200);
        String fingerprint = fingerprint("TRANSFER", from.toString(), to.toString(), money.toPlainString(), memo);
        return db.transaction(c -> {
            Mutation replay = replay(c, user, validKey, fingerprint);
            if (replay != null) return replay;
            LockedPair pair = lockAccounts(c, from, to);
            if (!user.id().equals(pair.source().ownerId())) {
                throw new ApiException(403, "FORBIDDEN", "Only the source account owner can send money");
            }
            if (pair.source().ownerId() == null || pair.destination().ownerId() == null) {
                throw validation("Transfers require two customer accounts");
            }
            requireActive(pair.source());
            requireActive(pair.destination());
            requireSameCurrency(pair);
            return post(c, user, validKey, fingerprint, pair, money, memo, "TRANSFER", null);
        });
    }

    /** Treasury credits represent an administrator recording externally verified settlement. */
    public Mutation credit(User admin, String key, UUID destination, String amount, String reference) {
        requireAdmin(admin);
        String validKey = idempotencyKey(key);
        BigDecimal money = money(amount);
        String memo = text(reference, "reference", 1, 200);
        String fingerprint = fingerprint("FUNDING", destination.toString(), money.toPlainString(), memo);
        return db.transaction(c -> {
            Mutation replay = replay(c, admin, validKey, fingerprint);
            if (replay != null) return replay;
            advisoryLock(c, "settlement:" + memo);
            try (PreparedStatement s = c.prepareStatement("SELECT 1 FROM transfers WHERE type = 'FUNDING' AND description = ?")) {
                s.setString(1, memo);
                try (ResultSet r = s.executeQuery()) {
                    if (r.next()) throw new ApiException(409, "SETTLEMENT_ALREADY_RECORDED", "Settlement reference has already been credited");
                }
            }
            Account target = findAccount(c, destination, false);
            if (target.ownerId() == null) throw validation("Only customer accounts can receive funding");
            LockedPair pair = lockAccounts(c, TREASURIES.get(target.currency()), destination);
            requireActive(pair.destination());
            return post(c, admin, validKey, fingerprint, pair, money, memo, "FUNDING", null);
        });
    }

    /** A reversal appends a compensating movement; it never edits or deletes financial history. */
    public Mutation reverse(User admin, String key, UUID originalId, String reason) {
        requireAdmin(admin);
        String validKey = idempotencyKey(key);
        String memo = text(reason, "reason", 1, 200);
        String fingerprint = fingerprint("REVERSAL", originalId.toString(), memo);
        return db.transaction(c -> {
            Mutation replay = replay(c, admin, validKey, fingerprint);
            if (replay != null) return replay;
            // Different idempotency keys still serialize on the original transfer.
            advisoryLock(c, "reversal:" + originalId);
            Transfer original = findTransfer(c, originalId);
            if (original.reversalOf() != null) throw validation("A compensating reversal cannot itself be reversed");
            if (original.status().equals("REVERSED")) {
                throw new ApiException(409, "TRANSFER_ALREADY_REVERSED", "Transfer already has a compensating reversal");
            }
            LockedPair pair = lockAccounts(c, original.destinationAccountId(), original.sourceAccountId());
            if (pair.source().status().equals("CLOSED") || pair.destination().status().equals("CLOSED")) {
                throw new ApiException(409, "ACCOUNT_CLOSED", "A closed account cannot participate in a reversal");
            }
            return post(c, admin, validKey, fingerprint, pair, original.amount(), memo, "REVERSAL", original.id());
        });
    }

    public Transfer getTransfer(User user, UUID id) {
        return db.read(c -> {
            Transfer transfer = findTransfer(c, id);
            if (!isAdmin(user)) {
                Account source = findAccount(c, transfer.sourceAccountId(), false);
                Account destination = findAccount(c, transfer.destinationAccountId(), false);
                if (!user.id().equals(source.ownerId()) && !user.id().equals(destination.ownerId())) {
                    throw notFound("Transfer");
                }
            }
            return transfer;
        });
    }

    public Page<Transfer> history(User user, UUID accountId, int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0 || offset > 1_000_000) {
            throw validation("limit must be 1..100 and offset must be 0..1000000");
        }
        return db.read(c -> {
            requireAccess(user, findAccount(c, accountId, false));
            List<Transfer> transfers = new ArrayList<>();
            try (PreparedStatement s = c.prepareStatement(TRANSFER_SELECT + """
                    WHERE t.source_account_id = ? OR t.destination_account_id = ?
                    ORDER BY t.created_at DESC, t.id DESC LIMIT ? OFFSET ?
                    """)) {
                s.setObject(1, accountId);
                s.setObject(2, accountId);
                s.setInt(3, limit);
                s.setInt(4, offset);
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) transfers.add(transfer(r));
                }
            }
            return new Page<>(List.copyOf(transfers), limit, offset);
        });
    }

    public Account setAccountStatus(User admin, UUID id, String status) {
        requireAdmin(admin);
        if (!List.of("ACTIVE", "FROZEN", "CLOSED").contains(status == null ? "" : status)) {
            throw validation("status must be ACTIVE, FROZEN or CLOSED");
        }
        return db.transaction(c -> {
            Account account = findAccount(c, id, true);
            if (account.ownerId() == null) throw validation("System treasury status cannot be changed");
            if (account.status().equals("CLOSED") && !status.equals("CLOSED")) {
                throw new ApiException(409, "ACCOUNT_CLOSED", "Closed accounts cannot reopen");
            }
            if (status.equals("CLOSED") && account.balance().signum() != 0) {
                throw new ApiException(409, "ACCOUNT_NOT_EMPTY", "Account balance must be zero before closing");
            }
            try (PreparedStatement s = c.prepareStatement("UPDATE accounts SET status = ? WHERE id = ? RETURNING *")) {
                s.setString(1, status);
                s.setObject(2, id);
                try (ResultSet r = s.executeQuery()) {
                    r.next();
                    Account updated = account(r);
                    audit(c, admin, "ACCOUNT_STATUS_CHANGED", id, account.status() + " -> " + status);
                    return updated;
                }
            }
        });
    }

    public Reconciliation reconcile(User admin) {
        requireAdmin(admin);
        // One statement gives every count and mismatch the same PostgreSQL MVCC snapshot.
        return db.read(c -> {
            String sql = """
                    WITH balances AS (
                      SELECT a.id, a.balance, coalesce(sum(e.delta), 0) AS journal_balance
                      FROM accounts a LEFT JOIN ledger_entries e ON e.account_id = a.id GROUP BY a.id
                    ), invalid AS (
                      SELECT t.id FROM transfers t LEFT JOIN ledger_entries e ON e.transfer_id = t.id
                      JOIN accounts s ON s.id = t.source_account_id
                      JOIN accounts d ON d.id = t.destination_account_id
                      GROUP BY t.id,s.currency,d.currency
                      HAVING count(e.id) <> 2 OR coalesce(sum(e.delta),0) <> 0
                         OR count(e.id) FILTER (WHERE
                            (e.account_id=t.source_account_id AND e.delta=-t.amount) OR
                            (e.account_id=t.destination_account_id AND e.delta=t.amount)) <> 2
                         OR s.currency <> t.currency OR d.currency <> t.currency
                    )
                    SELECT (SELECT count(*) FROM accounts) AS accounts_count,
                           (SELECT count(*) FROM transfers) AS transfers_count,
                           (SELECT count(*) FROM invalid) AS invalid_count,
                           b.id, b.balance, b.journal_balance
                    FROM (SELECT 1) anchor LEFT JOIN balances b ON b.balance <> b.journal_balance
                    ORDER BY b.id
                    """;
            List<BalanceMismatch> mismatches = new ArrayList<>();
            long accounts = 0;
            long transfers = 0;
            long invalid = 0;
            try (PreparedStatement s = c.prepareStatement(sql); ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    accounts = r.getLong("accounts_count");
                    transfers = r.getLong("transfers_count");
                    invalid = r.getLong("invalid_count");
                    UUID id = r.getObject("id", UUID.class);
                    if (id != null) mismatches.add(new BalanceMismatch(id, r.getBigDecimal("balance"), r.getBigDecimal("journal_balance")));
                }
            }
            return new Reconciliation(mismatches.isEmpty() && invalid == 0, accounts, transfers, List.copyOf(mismatches), invalid);
        });
    }

    private Mutation post(Connection c, User actor, String key, String fingerprint, LockedPair pair,
                          BigDecimal amount, String description, String type, UUID reversalOf) throws SQLException {
        if (pair.source().ownerId() != null && pair.source().balance().compareTo(amount) < 0) {
            throw new ApiException(422, "INSUFFICIENT_FUNDS", "Source account has insufficient available balance");
        }
        BigDecimal sourceBalance = pair.source().balance().subtract(amount);
        BigDecimal destinationBalance = pair.destination().balance().add(amount);
        if (sourceBalance.abs().compareTo(MAX_BALANCE) > 0 || destinationBalance.abs().compareTo(MAX_BALANCE) > 0) {
            throw new ApiException(422, "BALANCE_LIMIT", "Movement exceeds the supported account balance range");
        }
        UUID id = UUID.randomUUID();
        try (PreparedStatement s = c.prepareStatement("""
                INSERT INTO transfers(id,actor_id,source_account_id,destination_account_id,amount,currency,description,type,reversal_of)
                VALUES (?,?,?,?,?,?,?,?,?)
                """)) {
            s.setObject(1, id);
            s.setObject(2, actor.id());
            s.setObject(3, pair.source().id());
            s.setObject(4, pair.destination().id());
            s.setBigDecimal(5, amount);
            s.setString(6, pair.source().currency());
            s.setString(7, description);
            s.setString(8, type);
            s.setObject(9, reversalOf);
            s.executeUpdate();
        }
        // Both rows are already locked in UUID order. Any later exception rolls all writes back.
        updateBalance(c, pair.source().id(), sourceBalance);
        updateBalance(c, pair.destination().id(), destinationBalance);
        try (PreparedStatement s = c.prepareStatement("INSERT INTO ledger_entries(transfer_id,account_id,delta) VALUES (?,?,?),(?,?,?)")) {
            s.setObject(1, id);
            s.setObject(2, pair.source().id());
            s.setBigDecimal(3, amount.negate());
            s.setObject(4, id);
            s.setObject(5, pair.destination().id());
            s.setBigDecimal(6, amount);
            s.executeUpdate();
        }
        try (PreparedStatement s = c.prepareStatement("INSERT INTO idempotency_keys(actor_id,key,request_hash,transfer_id) VALUES (?,?,?,?)")) {
            s.setObject(1, actor.id());
            s.setString(2, key);
            s.setString(3, fingerprint);
            s.setObject(4, id);
            s.executeUpdate();
        }
        audit(c, actor, type + "_COMPLETED", id, amount.toPlainString() + " " + pair.source().currency());
        return new Mutation(findTransfer(c, id), false);
    }

    private Mutation replay(Connection c, User actor, String key, String fingerprint) throws SQLException {
        // A database lock coordinates independent JVMs, not just threads inside this process.
        advisoryLock(c, "idempotency:" + actor.id() + ":" + key);
        try (PreparedStatement s = c.prepareStatement("SELECT request_hash,transfer_id FROM idempotency_keys WHERE actor_id = ? AND key = ?")) {
            s.setObject(1, actor.id());
            s.setString(2, key);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) return null;
                if (!fingerprint.equals(r.getString("request_hash"))) {
                    throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "Idempotency key was already used for a different request");
                }
                return new Mutation(findTransfer(c, r.getObject("transfer_id", UUID.class)), true);
            }
        }
    }

    private static void advisoryLock(Connection c, String value) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
            s.setString(1, value);
            s.execute();
        }
    }

    private record LockedPair(Account source, Account destination) {}

    private static LockedPair lockAccounts(Connection c, UUID from, UUID to) throws SQLException {
        // The same ordering is used for A->B and B->A to avoid a lock-order deadlock.
        UUID first = from.toString().compareTo(to.toString()) < 0 ? from : to;
        UUID second = first.equals(from) ? to : from;
        Account a = findAccount(c, first, true);
        Account b = findAccount(c, second, true);
        return first.equals(from) ? new LockedPair(a, b) : new LockedPair(b, a);
    }

    private static Account findAccount(Connection c, UUID id, boolean lock) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT * FROM accounts WHERE id = ?" + (lock ? " FOR UPDATE" : ""))) {
            s.setObject(1, id);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw notFound("Account");
                return account(r);
            }
        }
    }

    private static Transfer findTransfer(Connection c, UUID id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(TRANSFER_SELECT + " WHERE t.id = ?")) {
            s.setObject(1, id);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw notFound("Transfer");
                return transfer(r);
            }
        }
    }

    private static Account account(ResultSet r) throws SQLException {
        return new Account(r.getObject("id", UUID.class), r.getObject("owner_id", UUID.class), r.getString("label"),
                r.getString("currency"), r.getBigDecimal("balance"), r.getString("status"), r.getTimestamp("created_at").toInstant());
    }

    private static Transfer transfer(ResultSet r) throws SQLException {
        return new Transfer(r.getObject("id", UUID.class), r.getObject("source_account_id", UUID.class),
                r.getObject("destination_account_id", UUID.class), r.getBigDecimal("amount"), r.getString("currency"),
                r.getString("description"), r.getString("status"), r.getObject("reversal_of", UUID.class),
                r.getTimestamp("created_at").toInstant());
    }

    private static void updateBalance(Connection c, UUID id, BigDecimal balance) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE accounts SET balance = ? WHERE id = ?")) {
            s.setBigDecimal(1, balance);
            s.setObject(2, id);
            if (s.executeUpdate() != 1) throw new SQLException("Locked account disappeared");
        }
    }

    private static void audit(Connection c, User actor, String action, UUID entity, String details) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO audit_events(actor_id,action,entity_id,details) VALUES (?,?,?,?)")) {
            s.setObject(1, actor.id());
            s.setString(2, action);
            s.setObject(3, entity);
            s.setString(4, details);
            s.executeUpdate();
        }
    }

    private static void requireSameCurrency(LockedPair pair) {
        if (!pair.source().currency().equals(pair.destination().currency())) {
            throw new ApiException(422, "CURRENCY_MISMATCH", "Accounts must use the same currency; conversion is not supported");
        }
    }

    private static void requireActive(Account account) {
        if (!account.status().equals("ACTIVE")) {
            throw new ApiException(409, "ACCOUNT_NOT_ACTIVE", "Both accounts must be active");
        }
    }

    private static void requireAccess(User user, Account account) {
        if (!isAdmin(user) && !user.id().equals(account.ownerId())) throw notFound("Account");
    }

    private static boolean isAdmin(User user) {
        return "ADMIN".equals(user.role());
    }

    private static void requireAdmin(User user) {
        if (!isAdmin(user)) throw new ApiException(403, "FORBIDDEN", "Administrator role is required");
    }

    private static String idempotencyKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{8,128}")) {
            throw validation("Idempotency-Key must contain 8..128 letters, digits, periods, underscores, colons or hyphens");
        }
        return key;
    }

    private static BigDecimal money(String value) {
        if (value == null || !value.matches("(?:0|[1-9][0-9]{0,11})(?:\\.[0-9]{1,2})?")) {
            throw validation("amount must be a decimal string with up to 12 integer digits and 2 decimal places");
        }
        BigDecimal amount = new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
        if (amount.signum() <= 0) throw validation("amount must be greater than zero");
        return amount;
    }

    private static String text(String value, String field, int min, int max) {
        String cleaned = value == null ? "" : value.strip();
        if (cleaned.length() < min || cleaned.length() > max || cleaned.codePoints().anyMatch(Character::isISOControl)) {
            throw validation(field + " must contain " + min + ".." + max + " characters without control characters");
        }
        return cleaned;
    }

    private static String optionalText(String value, String field, int max) {
        return text(value, field, 0, max);
    }

    private static String fingerprint(String... parts) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\u0000", parts).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM must support SHA-256", e);
        }
    }

    private static ApiException notFound(String entity) {
        return new ApiException(404, "NOT_FOUND", entity + " was not found");
    }

    private static ApiException validation(String message) {
        return new ApiException(400, "VALIDATION_ERROR", message);
    }
}
