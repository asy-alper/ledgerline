package dev.ledgerline.auth;

import dev.ledgerline.api.ApiException;
import dev.ledgerline.db.Database;
import dev.ledgerline.integration.PostgresFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL is required: atomic upserts and token expiry are persistence guarantees. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthSecurityIT {
    private static final String PASSWORD = "Learning-Java-2026!";
    private PostgresFixture fixture;
    private Database database;
    private AuthService auth;

    @BeforeAll
    void startDatabase() throws Exception {
        fixture = PostgresFixture.open();
        database = fixture.database();
        auth = new AuthService(database);
    }

    @AfterAll
    void stopDatabase() throws Exception {
        if (fixture != null) fixture.close();
    }

    @Test
    void bootstrapCannotPromoteAnExistingCustomerOrResetExistingAdminPassword() {
        String customerEmail = email();
        AuthService.User customer = auth.register(customerEmail, "Test Customer", PASSWORD, source());
        ApiException conflict = assertThrows(ApiException.class,
                () -> auth.bootstrapAdmin(customerEmail, "Some-other-password!"));
        assertEquals(409, conflict.status());
        assertEquals("ADMIN_BOOTSTRAP_CONFLICT", conflict.code());
        AuthService.Session customerSession = auth.login(customerEmail, PASSWORD, source());
        assertEquals("USER", auth.authenticate(customerSession.token()).role());
        assertEquals(customer.id(), customerSession.user().id());

        String adminEmail = email();
        auth.bootstrapAdmin(adminEmail, PASSWORD);
        auth.bootstrapAdmin(adminEmail, "Replacement-password-is-ignored!");
        AuthService.Session adminSession = auth.login(adminEmail, PASSWORD, source());
        assertEquals("ADMIN", adminSession.user().role());
        assertStatus(401, () -> auth.login(adminEmail, "Replacement-password-is-ignored!", source()));
    }

    @Test
    void sessionsStoreOnlyHashesAndSupportLogoutExpiryAndCurrentRoleLookup() {
        String email = email();
        AuthService.User user = auth.register(email, "Session Customer", PASSWORD, source());
        AuthService.Session session = auth.login(email, PASSWORD, source());
        assertEquals(43, session.token().length());
        String storedHash = database.read(connection -> {
            try (var statement = connection.prepareStatement("SELECT token_hash FROM sessions WHERE user_id = ?")) {
                statement.setObject(1, user.id());
                try (var result = statement.executeQuery()) {
                    assertTrue(result.next());
                    return result.getString(1);
                }
            }
        });
        assertEquals(64, storedHash.length());
        assertNotEquals(session.token(), storedHash);
        assertEquals(sha256(session.token()), storedHash);
        assertEquals(user.id(), auth.authenticate(session.token()).id());
        setRole(user.id(), "ADMIN");
        assertEquals("ADMIN", auth.authenticate(session.token()).role());
        setRole(user.id(), "USER");
        assertEquals("USER", auth.authenticate(session.token()).role());

        auth.logout(session.token());
        assertStatus(401, () -> auth.authenticate(session.token()));
        auth.logout(session.token());

        AuthService.Session expiring = auth.login(email, PASSWORD, source());
        database.transaction(connection -> {
            try (var statement = connection.prepareStatement("UPDATE sessions SET expires_at = now() - interval '1 second' WHERE token_hash = ?")) {
                statement.setString(1, sha256(expiring.token()));
                statement.executeUpdate();
            }
            return null;
        });
        assertStatus(401, () -> auth.authenticate(expiring.token()));
    }

    @Test
    void malformedAndFabricatedTokensCannotAuthenticate() {
        for (String token : new String[]{null, "", "a", "a".repeat(42), "a".repeat(43),
                "a".repeat(44), "Bearer " + "a".repeat(43), "!".repeat(43), "ş".repeat(43)}) {
            assertStatus(401, () -> auth.authenticate(token));
        }
    }

    @Test
    void failedLoginCountersCommitAndCannotBeBypassedByChangingTheSourceAddress() {
        String email = email();
        auth.register(email, "Throttled Customer", PASSWORD, source());
        String client = source();
        for (int attempt = 0; attempt < 10; attempt++) {
            assertStatus(401, () -> auth.login(email, "incorrect-password", client));
        }
        assertStatus(429, () -> auth.login(email, PASSWORD, client));
        assertStatus(429, () -> auth.login(email, PASSWORD, source()));
        assertEquals(12, attempts("login:email:" + email));
    }

    @Test
    void concurrentLoginAttemptsCannotBypassTheSharedEmailCounter() throws Exception {
        String email = email();
        String client = source();
        List<Callable<Integer>> work = new ArrayList<>();
        for (int attempt = 0; attempt < 30; attempt++) {
            work.add(() -> {
                try {
                    auth.login(email, "incorrect-password", client);
                    return 200;
                } catch (ApiException exception) {
                    return exception.status();
                }
            });
        }
        List<Integer> outcomes = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(12)) {
            for (var future : executor.invokeAll(work)) outcomes.add(future.get());
        }
        assertEquals(10, outcomes.stream().filter(status -> status == 401).count());
        assertEquals(20, outcomes.stream().filter(status -> status == 429).count());
        assertEquals(30, attempts("login:email:" + email));
    }

    @Test
    void exhaustedIpBucketCannotGrowTheTableWithNewEmailAddresses() {
        String client = source();
        seedBucket("login:client:" + client, 60, false);
        String email = email();
        assertStatus(429, () -> auth.login(email, "incorrect-password", client));
        assertEquals(61, attempts("login:client:" + client));
        assertEquals(0, attempts("login:email:" + email));

        String registerClient = source();
        seedBucket("register:client:" + registerClient, 30, false);
        String newEmail = email();
        assertStatus(429, () -> auth.register(newEmail, "Blocked Customer", PASSWORD, registerClient));
        assertEquals(31, attempts("register:client:" + registerClient));
        assertEquals(0, attempts("register:email:" + newEmail));
    }

    @Test
    void registrationCountsConflictsAndResetsExpiredWindows() {
        String email = email();
        String client = source();
        auth.register(email, "Duplicate Customer", PASSWORD, client);
        for (int attempt = 0; attempt < 4; attempt++) {
            assertStatus(409, () -> auth.register(email, "Duplicate Customer", PASSWORD, client));
        }
        assertStatus(429, () -> auth.register(email, "Duplicate Customer", PASSWORD, client));
        assertEquals(6, attempts("register:email:" + email));

        seedBucket("login:email:" + email, 10, true);
        AuthService.Session session = auth.login(email, PASSWORD, source());
        assertEquals(email, session.user().email());
        assertEquals(1, attempts("login:email:" + email));
    }

    @Test
    void credentialsAreNormalizedAndSqlInjectionIsAnOrdinaryFailedLogin() {
        String email = email();
        AuthService.User registered = auth.register(" " + email.toUpperCase() + " ", " SQL Customer ", PASSWORD, source());
        assertEquals(email, registered.email());
        assertEquals("SQL Customer", registered.fullName());
        assertEquals("USER", registered.role());
        assertEquals(registered.id(), auth.login(email, PASSWORD, source()).user().id());
        assertStatus(401, () -> auth.login("' OR 1=1 --", PASSWORD, source()));
        assertStatus(401, () -> auth.login(email, "' OR 1=1 --", source()));
        assertStatus(400, () -> auth.register(email(), "Name", "short", source()));
        assertStatus(400, () -> auth.register(email(), "Name", "x".repeat(129), source()));
    }

    private void setRole(UUID userId, String role) {
        database.transaction(connection -> {
            try (var statement = connection.prepareStatement("UPDATE users SET role = ? WHERE id = ?")) {
                statement.setString(1, role);
                statement.setObject(2, userId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private void seedBucket(String value, int attempts, boolean expired) {
        database.transaction(connection -> {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO auth_rate_limits(bucket_key, window_start, attempts)
                    VALUES (?, now() - (? * interval '1 minute'), ?)
                    ON CONFLICT (bucket_key) DO UPDATE SET window_start = EXCLUDED.window_start, attempts = EXCLUDED.attempts
                    """)) {
                statement.setString(1, sha256(value));
                statement.setInt(2, expired ? 16 : 0);
                statement.setInt(3, attempts);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private int attempts(String value) {
        return database.read(connection -> {
            try (var statement = connection.prepareStatement("SELECT attempts FROM auth_rate_limits WHERE bucket_key = ?")) {
                statement.setString(1, sha256(value));
                try (var result = statement.executeQuery()) {
                    return result.next() ? result.getInt(1) : 0;
                }
            }
        });
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String email() { return "auth-" + UUID.randomUUID() + "@example.test"; }
    private static String source() { return "test-source-" + UUID.randomUUID(); }
    private static void assertStatus(int expected, Runnable action) {
        assertEquals(expected, assertThrows(ApiException.class, action::run).status());
    }
}
