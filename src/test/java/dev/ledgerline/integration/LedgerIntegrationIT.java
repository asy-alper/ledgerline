package dev.ledgerline.integration;

import dev.ledgerline.api.ApiException;
import dev.ledgerline.auth.AuthService;
import dev.ledgerline.auth.AuthService.User;
import dev.ledgerline.db.Database;
import dev.ledgerline.ledger.LedgerService;
import dev.ledgerline.ledger.LedgerService.Account;
import dev.ledgerline.ledger.LedgerService.Mutation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Tests intentionally run against PostgreSQL, never an in-memory substitute. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LedgerIntegrationIT {
    private static final String PASSWORD = "Integration only! 2026-password";
    private PostgresFixture postgres;
    private Database db;
    private LedgerService ledger;
    private AuthService auth;
    private User admin;
    private User alice;
    private User bob;
    private User outsider;

    @BeforeAll
    void startPostgresAndMigrateIsolatedSchema() throws Exception {
        postgres = PostgresFixture.open();
        db = postgres.database();
        auth = new AuthService(db);
        ledger = new LedgerService(db);
        auth.bootstrapAdmin("admin@example.test", PASSWORD);
        admin = auth.login("admin@example.test", PASSWORD, "bootstrap").user();
        alice = auth.register("alice@example.test", "Alice Example", PASSWORD);
        bob = auth.register("bob@example.test", "Bob Example", PASSWORD);
        outsider = auth.register("outsider@example.test", "Eve Example", PASSWORD);
    }

    @AfterAll
    void removeOnlyOurGeneratedSchema() throws Exception {
        if (postgres != null) postgres.close();
    }

    @Test
    void transferMovesExactMoneyAndAddsTwoBalancedJournalLegs() {
        Account source = funded(alice, "TRY", "100.00");
        Account destination = account(bob, "TRY");
        Mutation result = transfer(alice, source, destination, "25.45");

        assertFalse(result.replayed());
        amount("74.55", balance(alice, source));
        amount("25.45", balance(bob, destination));
        assertEquals(2, count("ledger_entries", "transfer_id", result.transfer().id()));
        assertEquals(0, journalSum(result.transfer().id()).compareTo(BigDecimal.ZERO));
        assertEquals(result.transfer().id(), ledger.getTransfer(alice, result.transfer().id()).id());
        assertEquals(result.transfer().id(), ledger.getTransfer(bob, result.transfer().id()).id());
        assertTrue(ledger.reconcile(admin).balanced());
    }

    @Test
    void repeatedKeyReturnsSameTransferWithoutSendingTwice() {
        Account source = funded(alice, "TRY", "100.00");
        Account destination = account(bob, "TRY");
        String key = key();
        Mutation first = ledger.transfer(alice, key, source.id(), destination.id(), "20.00", "rent");
        Mutation replay = new LedgerService(db).transfer(alice, key, source.id(), destination.id(), "20.00", "rent");

        assertTrue(replay.replayed());
        assertEquals(first.transfer().id(), replay.transfer().id());
        amount("80.00", balance(alice, source));
        amount("20.00", balance(bob, destination));
        assertEquals(1, count("transfers", "source_account_id", source.id()));
    }

    @Test
    void sameKeyWithDifferentPayloadIsConflict() {
        Account source = funded(alice, "TRY", "100.00");
        Account destination = account(bob, "TRY");
        String key = key();
        ledger.transfer(alice, key, source.id(), destination.id(), "20.00", "rent");

        assertCode("IDEMPOTENCY_CONFLICT", 409,
                () -> ledger.transfer(alice, key, source.id(), destination.id(), "21.00", "rent"));
        assertCode("IDEMPOTENCY_CONFLICT", 409,
                () -> ledger.transfer(alice, key, source.id(), destination.id(), "20.00", "changed"));
        amount("80.00", balance(alice, source));
    }

    @Test
    void concurrentIdenticalRequestsCommitOnlyOnce() throws Exception {
        Account source = funded(alice, "TRY", "100.00");
        Account destination = account(bob, "TRY");
        String key = key();
        List<Mutation> results = concurrently(8,
                () -> ledger.transfer(alice, key, source.id(), destination.id(), "11.11", "parallel"));

        assertEquals(1, results.stream().map(r -> r.transfer().id()).distinct().count());
        assertEquals(1, results.stream().filter(r -> !r.replayed()).count());
        amount("88.89", balance(alice, source));
        amount("11.11", balance(bob, destination));
        assertTrue(ledger.reconcile(admin).balanced());
    }

    @Test
    void concurrentDifferentKeysCannotOverdrawAnAccount() throws Exception {
        Account source = funded(alice, "TRY", "100.00");
        Account destination = account(bob, "TRY");
        List<Boolean> outcomes = concurrently(8, () -> {
            try {
                transfer(alice, source, destination, "30.00");
                return true;
            } catch (ApiException exception) {
                assertEquals("INSUFFICIENT_FUNDS", exception.code());
                return false;
            }
        });

        assertEquals(3, outcomes.stream().filter(Boolean::booleanValue).count());
        amount("10.00", balance(alice, source));
        amount("90.00", balance(bob, destination));
        assertTrue(ledger.reconcile(admin).balanced());
    }

    @Test
    void oppositeDirectionTransfersFinishWithoutDeadlockOrLostUpdates() throws Exception {
        Account a = funded(alice, "TRY", "100.00");
        Account b = funded(bob, "TRY", "100.00");
        List<Callable<Mutation>> operations = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            operations.add(() -> transfer(alice, a, b, "3.50"));
            operations.add(() -> transfer(bob, b, a, "3.50"));
        }
        runConcurrent(operations);

        amount("100.00", balance(alice, a));
        amount("100.00", balance(bob, b));
        assertTrue(ledger.reconcile(admin).balanced());
    }

    @Test
    void failedTransferRollsBackDebitJournalAuditAndIdempotency() {
        Account source = funded(alice, "TRY", "100.00");
        Account destination = account(bob, "TRY");
        String key = key();
        long auditBefore = total("audit_events");
        long journalBefore = total("ledger_entries");
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String function = "fail_credit_" + suffix;
        String trigger = "fail_credit_trigger_" + suffix;
        db.transaction(connection -> {
            connection.createStatement().execute("CREATE FUNCTION " + function + "() RETURNS trigger "
                    + "LANGUAGE plpgsql AS $$ BEGIN IF NEW.id = '" + destination.id() + "'::uuid "
                    + "AND NEW.balance > OLD.balance THEN RAISE EXCEPTION 'injected PostgreSQL failure'; "
                    + "END IF; RETURN NEW; END $$");
            connection.createStatement().execute("CREATE TRIGGER " + trigger
                    + " BEFORE UPDATE ON accounts FOR EACH ROW EXECUTE FUNCTION " + function + "()");
            return null;
        });
        try {
            assertThrows(RuntimeException.class,
                    () -> ledger.transfer(alice, key, source.id(), destination.id(), "30.00", "rollback"));
            amount("100.00", balance(alice, source));
            amount("0.00", balance(bob, destination));
            assertEquals(0, count("transfers", "source_account_id", source.id()));
            assertEquals(journalBefore, total("ledger_entries"));
            assertEquals(auditBefore, total("audit_events"));
            assertEquals(0, countKey(alice.id(), key));
        } finally {
            db.transaction(connection -> {
                connection.createStatement().execute("DROP TRIGGER " + trigger + " ON accounts");
                connection.createStatement().execute("DROP FUNCTION " + function + "()");
                return null;
            });
        }
        Mutation retry = ledger.transfer(alice, key, source.id(), destination.id(), "30.00", "rollback");
        assertFalse(retry.replayed());
        amount("70.00", balance(alice, source));
        assertTrue(ledger.reconcile(admin).balanced());
    }

    @Test
    void insufficientFundsLeavesNoPartialTransferAndSameKeyCanBeRetried() {
        Account source = account(alice, "TRY");
        Account destination = account(bob, "TRY");
        String key = key();

        assertCode("INSUFFICIENT_FUNDS", 422,
                () -> ledger.transfer(alice, key, source.id(), destination.id(), "10.00", "retry"));
        assertEquals(0, count("transfers", "source_account_id", source.id()));
        assertEquals(0, countKey(alice.id(), key));
        ledger.credit(admin, key(), source.id(), "10.00", "test-funding");
        assertFalse(ledger.transfer(alice, key, source.id(), destination.id(), "10.00", "retry").replayed());
        amount("0.00", balance(alice, source));
        amount("10.00", balance(bob, destination));
    }

    @Test
    void ownersCannotSpendOrInspectSomeoneElsesMoney() {
        Account source = funded(alice, "TRY", "50.00");
        Account destination = account(bob, "TRY");
        Mutation payment = transfer(alice, source, destination, "5.00");

        assertThrows(ApiException.class, () -> ledger.getAccount(outsider, source.id()));
        assertThrows(ApiException.class, () -> ledger.history(outsider, source.id(), 20, 0));
        assertThrows(ApiException.class, () -> ledger.getTransfer(outsider, payment.transfer().id()));
        assertThrows(ApiException.class,
                () -> ledger.transfer(bob, key(), source.id(), destination.id(), "1.00", "stolen"));
        assertThrows(ApiException.class,
                () -> ledger.credit(alice, key(), source.id(), "1000.00", "unauthorized"));
        assertThrows(ApiException.class, () -> ledger.reconcile(alice));
        amount("45.00", balance(alice, source));
    }

    @Test
    void currenciesAndInvalidAmountsAreRejectedWithoutChanges() {
        Account source = funded(alice, "TRY", "50.00");
        Account otherCurrency = account(bob, "USD");
        Account destination = account(bob, "TRY");
        assertThrows(ApiException.class,
                () -> transfer(alice, source, otherCurrency, "1.00"));
        for (String amount : List.of("0", "-1", "0.001", "1e2", "NaN", "1000000000000000000000")) {
            assertThrows(ApiException.class, () -> transfer(alice, source, destination, amount), amount);
        }
        assertThrows(ApiException.class, () -> transfer(alice, source, source, "1.00"));
        assertThrows(ApiException.class, () -> ledger.createAccount(alice, "Unsupported", "XYZ"));
        amount("50.00", balance(alice, source));
        amount("0.00", balance(bob, destination));
    }

    @Test
    void frozenAccountBlocksTransfersAndOnlyAdminCanChangeStatus() {
        Account source = funded(alice, "TRY", "50.00");
        Account destination = account(bob, "TRY");
        assertThrows(ApiException.class, () -> ledger.setAccountStatus(alice, source.id(), "FROZEN"));
        ledger.setAccountStatus(admin, source.id(), "FROZEN");

        assertCode("ACCOUNT_NOT_ACTIVE", 409, () -> transfer(alice, source, destination, "1.00"));
        ledger.setAccountStatus(admin, source.id(), "ACTIVE");
        ledger.setAccountStatus(admin, destination.id(), "FROZEN");
        assertCode("ACCOUNT_NOT_ACTIVE", 409, () -> transfer(alice, source, destination, "1.00"));
        amount("50.00", balance(alice, source));
    }

    @Test
    void historyIsPaginatedAndStable() {
        Account source = funded(alice, "TRY", "50.00");
        Account destination = account(bob, "TRY");
        transfer(alice, source, destination, "1.00");
        transfer(alice, source, destination, "2.00");
        transfer(alice, source, destination, "3.00");

        var first = ledger.history(bob, destination.id(), 2, 0);
        var second = ledger.history(bob, destination.id(), 2, 2);
        assertEquals(2, first.items().size());
        assertEquals(1, second.items().size());
        assertFalse(first.items().stream().map(t -> t.id()).toList().contains(second.items().getFirst().id()));
        assertThrows(ApiException.class, () -> ledger.history(bob, destination.id(), 1001, 0));
        assertThrows(ApiException.class, () -> ledger.history(bob, destination.id(), 1, -1));
    }

    @Test
    void reversalIsCompensatingAuditableAndExactlyOnce() {
        Account source = funded(alice, "TRY", "100.00");
        Account destination = account(bob, "TRY");
        Mutation original = transfer(alice, source, destination, "25.00");
        assertThrows(ApiException.class,
                () -> ledger.reverse(alice, key(), original.transfer().id(), "not authorized"));
        String reversalKey = key();
        Mutation reversed = ledger.reverse(admin, reversalKey, original.transfer().id(), "customer support refund");

        assertEquals(original.transfer().id(), reversed.transfer().reversalOf());
        assertEquals(destination.id(), reversed.transfer().sourceAccountId());
        assertEquals(source.id(), reversed.transfer().destinationAccountId());
        amount("100.00", balance(alice, source));
        amount("0.00", balance(bob, destination));
        assertEquals("REVERSED", ledger.getTransfer(alice, original.transfer().id()).status());
        assertTrue(ledger.reverse(admin, reversalKey, original.transfer().id(), "customer support refund").replayed());
        assertCode("TRANSFER_ALREADY_REVERSED", 409,
                () -> ledger.reverse(admin, key(), original.transfer().id(), "second refund"));
        assertEquals(2, count("ledger_entries", "transfer_id", reversed.transfer().id()));
        assertTrue(ledger.reconcile(admin).balanced());
    }

    @Test
    void reversalCannotCreateMoneyAfterRecipientSpendsFunds() {
        Account source = funded(alice, "TRY", "100.00");
        Account destination = account(bob, "TRY");
        Account third = account(outsider, "TRY");
        Mutation original = transfer(alice, source, destination, "25.00");
        transfer(bob, destination, third, "25.00");

        assertCode("INSUFFICIENT_FUNDS", 422,
                () -> ledger.reverse(admin, key(), original.transfer().id(), "spent refund"));
        amount("75.00", balance(alice, source));
        amount("0.00", balance(bob, destination));
        amount("25.00", balance(outsider, third));
        assertEquals("COMPLETED", ledger.getTransfer(alice, original.transfer().id()).status());
        assertTrue(ledger.reconcile(admin).balanced());
    }

    @Test
    void fundingIsBalancedAndIdempotent() {
        Account destination = account(alice, "EUR");
        String key = key();
        Mutation first = ledger.credit(admin, key, destination.id(), "123.45", "external-settlement-123");
        Mutation replay = ledger.credit(admin, key, destination.id(), "123.45", "external-settlement-123");

        assertTrue(replay.replayed());
        assertEquals(first.transfer().id(), replay.transfer().id());
        amount("123.45", balance(alice, destination));
        assertEquals(0, journalSum(first.transfer().id()).compareTo(BigDecimal.ZERO));
        assertTrue(ledger.reconcile(admin).balanced());
    }

    @Test
    void sameExternalSettlementCannotBeCreditedWithAnotherRequestKey() {
        Account destination = account(alice, "TRY");
        String reference = "bank-a:" + key();
        String firstKey = key();
        Mutation first = ledger.credit(admin, firstKey, destination.id(), "10.00", reference);
        assertCode("SETTLEMENT_ALREADY_RECORDED", 409,
                () -> ledger.credit(admin, key(), destination.id(), "10.00", reference));
        assertTrue(ledger.credit(admin, firstKey, destination.id(), "10.00", reference).replayed());
        amount("10.00", balance(alice, destination));
        assertEquals(2, count("ledger_entries", "transfer_id", first.transfer().id()));
    }

    @Test
    void databaseRejectsBalanceEditsOutsideBalancedJournal() {
        Account account = funded(alice, "TRY", "20.00");
        assertThrows(RuntimeException.class, () -> db.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE accounts SET balance = balance + 1.00 WHERE id = ?")) {
                statement.setObject(1, account.id());
                statement.executeUpdate();
            }
            return null;
        }));
        amount("20.00", balance(alice, account));
    }

    @Test
    void databaseRejectsTransferWithoutJournalAndJournalEdits() {
        Account source = funded(alice, "TRY", "20.00");
        Account destination = account(bob, "TRY");
        UUID orphan = UUID.randomUUID();
        assertThrows(RuntimeException.class, () -> db.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO transfers(id,actor_id,source_account_id,destination_account_id,
                                          amount,currency,description,type)
                    VALUES (?,?,?,?,1.00,'TRY','missing journal','TRANSFER')
                    """)) {
                statement.setObject(1, orphan);
                statement.setObject(2, alice.id());
                statement.setObject(3, source.id());
                statement.setObject(4, destination.id());
                statement.executeUpdate();
            }
            return null;
        }));
        assertEquals(0, count("transfers", "id", orphan));
        Mutation payment = transfer(alice, source, destination, "1.00");
        for (String sql : List.of("UPDATE ledger_entries SET delta = delta + 1 WHERE transfer_id = ?",
                "DELETE FROM ledger_entries WHERE transfer_id = ?",
                "UPDATE transfers SET description = 'tampered' WHERE id = ?",
                "DELETE FROM transfers WHERE id = ?",
                "UPDATE idempotency_keys SET key = 'tampered-key' WHERE transfer_id = ?",
                "DELETE FROM idempotency_keys WHERE transfer_id = ?",
                "UPDATE audit_events SET details = 'tampered' WHERE entity_id = ?",
                "DELETE FROM audit_events WHERE entity_id = ?")) {
            assertThrows(RuntimeException.class, () -> db.transaction(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setObject(1, payment.transfer().id());
                    statement.executeUpdate();
                }
                return null;
            }));
        }
        // A statement-level trigger also blocks bulk deletion that bypasses row DELETE triggers.
        for (String table : List.of("ledger_entries", "transfers", "idempotency_keys", "audit_events")) {
            assertThrows(RuntimeException.class, () -> db.transaction(connection -> {
                connection.createStatement().execute("TRUNCATE TABLE " + table + " CASCADE");
                return null;
            }));
        }
        assertTrue(ledger.reconcile(admin).balanced());
    }

    @Test
    void opaqueSessionsAreStoredHashedRevocableAndNeverGrantAdmin() {
        var session = auth.login(alice.email(), PASSWORD, "session-test");
        assertEquals(alice.id(), auth.authenticate(session.token()).id());
        assertEquals("USER", auth.authenticate(session.token()).role());
        long rawTokens = db.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM sessions WHERE token_hash = ?")) {
                statement.setString(1, session.token());
                try (var result = statement.executeQuery()) {
                    result.next();
                    return result.getLong(1);
                }
            }
        });
        assertEquals(0, rawTokens);
        auth.logout(session.token());
        assertThrows(ApiException.class, () -> auth.authenticate(session.token()));
        assertThrows(ApiException.class, () -> auth.authenticate("forged-token-value"));
        assertThrows(ApiException.class, () -> auth.login(alice.email(), "incorrect password", "bad-password"));
    }

    private Account account(User owner, String currency) {
        return ledger.createAccount(owner, "Integration " + key().substring(0, 8), currency);
    }

    private Account funded(User owner, String currency, String amount) {
        Account account = account(owner, currency);
        ledger.credit(admin, key(), account.id(), amount, "integration:" + key());
        return account;
    }

    private Mutation transfer(User owner, Account source, Account destination, String amount) {
        return ledger.transfer(owner, key(), source.id(), destination.id(), amount, "integration transfer");
    }

    private BigDecimal balance(User owner, Account account) {
        return ledger.getAccount(owner, account.id()).balance();
    }

    private static void amount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "Expected " + expected + ", got " + actual);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static void assertCode(String code, int status, Runnable operation) {
        ApiException exception = assertThrows(ApiException.class, operation::run);
        assertEquals(code, exception.code());
        assertEquals(status, exception.status());
    }

    private long total(String table) {
        return db.read(connection -> {
            try (var result = connection.createStatement().executeQuery("SELECT COUNT(*) FROM " + table)) {
                result.next();
                return result.getLong(1);
            }
        });
    }

    private long count(String table, String column, UUID id) {
        return db.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?")) {
                statement.setObject(1, id);
                try (var result = statement.executeQuery()) {
                    result.next();
                    return result.getLong(1);
                }
            }
        });
    }

    private long countKey(UUID actor, String key) {
        return db.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM idempotency_keys WHERE actor_id = ? AND key = ?")) {
                statement.setObject(1, actor);
                statement.setString(2, key);
                try (var result = statement.executeQuery()) {
                    result.next();
                    return result.getLong(1);
                }
            }
        });
    }

    private BigDecimal journalSum(UUID transfer) {
        return db.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COALESCE(SUM(delta),0) FROM ledger_entries WHERE transfer_id = ?")) {
                statement.setObject(1, transfer);
                try (var result = statement.executeQuery()) {
                    result.next();
                    return result.getBigDecimal(1);
                }
            }
        });
    }

    private static <T> List<T> concurrently(int count, Callable<T> operation) throws Exception {
        List<Callable<T>> operations = new ArrayList<>();
        for (int i = 0; i < count; i++) operations.add(operation);
        return runConcurrent(operations);
    }

    private static <T> List<T> runConcurrent(List<Callable<T>> operations) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(operations.size());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> operation : operations) {
                futures.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return operation.call();
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            return results;
        }
    }

}
