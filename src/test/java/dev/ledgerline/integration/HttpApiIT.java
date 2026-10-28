package dev.ledgerline.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ledgerline.api.ApiServer;
import dev.ledgerline.auth.AuthService;
import dev.ledgerline.db.Database;
import dev.ledgerline.ledger.LedgerService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual socket, JSON parser, router, authorization and PostgreSQL together. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpApiIT {
    private static final String PASSWORD = "HTTP integration! 2026-password";
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private PostgresFixture postgres;
    private ApiServer server;
    private AuthService auth;
    private LedgerService ledger;
    private AuthService.User alice;
    private AuthService.User bob;
    private AuthService.User admin;
    private String aliceToken;
    private String bobToken;
    private String adminToken;
    private String baseUrl;

    @BeforeAll
    void startHttpServerWithRealDatabase() throws Exception {
        postgres = PostgresFixture.open();
        Database database = postgres.database();
        auth = new AuthService(database);
        ledger = new LedgerService(database);
        auth.bootstrapAdmin("admin@example.test", PASSWORD);
        var adminSession = auth.login("admin@example.test", PASSWORD, "http-fixture");
        admin = adminSession.user();
        adminToken = adminSession.token();
        alice = auth.register("alice@example.test", "Alice HTTP", PASSWORD);
        bob = auth.register("bob@example.test", "Bob HTTP", PASSWORD);
        aliceToken = auth.login(alice.email(), PASSWORD, "http-fixture-alice").token();
        bobToken = auth.login(bob.email(), PASSWORD, "http-fixture-bob").token();
        server = new ApiServer("127.0.0.1", 0, database, auth, ledger);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.port();
    }

    @AfterAll
    void stopHttpServerAndDatabase() throws Exception {
        if (server != null) server.close();
        if (postgres != null) postgres.close();
        client.close();
    }

    @Test
    void fullHttpWorkflowIncludesRevocationHistoryAndIdempotency() throws Exception {
        String email = "http-" + key() + "@example.test";
        var registration = request("POST", "/v1/auth/register", null,
                Map.of("email", email, "fullName", "HTTP New User", "password", PASSWORD), null);
        assertEquals(201, registration.statusCode(), registration.body());
        assertEquals("USER", body(registration).path("role").asText());
        assertFalse(registration.body().contains("password"));
        var login = request("POST", "/v1/auth/login", null,
                Map.of("email", email, "password", PASSWORD), null);
        assertEquals(200, login.statusCode(), login.body());
        String token = body(login).path("token").asText();
        assertFalse(token.isBlank());
        assertEquals(email, body(request("GET", "/v1/me", token, null, null)).path("email").asText());

        var opened = request("POST", "/v1/accounts", token, Map.of("label", "Daily wallet", "currency", "TRY"), null);
        assertEquals(201, opened.statusCode(), opened.body());
        String source = body(opened).path("id").asText();
        String destination = account(bob).id().toString();
        assertEquals(201, request("POST", "/v1/admin/credits", adminToken,
                Map.of("destinationAccountId", source, "amount", "100.00", "reference", "settlement-123"), key()).statusCode());
        String key = key();
        Map<String, String> payment = Map.of("sourceAccountId", source, "destinationAccountId", destination,
                "amount", "12.34", "description", "HTTP payment");
        var first = request("POST", "/v1/transfers", token, payment, key);
        assertEquals(201, first.statusCode(), first.body());
        var replay = request("POST", "/v1/transfers", token, payment, key);
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals("true", replay.headers().firstValue("Idempotent-Replayed").orElseThrow());
        assertEquals(body(first).path("id").asText(), body(replay).path("id").asText());
        assertEquals("87.66", body(request("GET", "/v1/accounts/" + source, token, null, null)).path("balance").asText());
        var history = request("GET", "/v1/accounts/" + source + "/transactions?limit=10&offset=0", token, null, null);
        assertEquals(200, history.statusCode(), history.body());
        assertEquals(2, body(history).path("items").size());
        assertTrue(body(request("GET", "/v1/admin/reconciliation", adminToken, null, null)).path("balanced").asBoolean());

        assertEquals(204, request("POST", "/v1/auth/logout", token, null, null).statusCode());
        assertEquals(401, request("GET", "/v1/me", token, null, null).statusCode());
    }

    @Test
    void healthAndErrorsHaveSafeHeadersAndCorrelatedRequestIds() throws Exception {
        assertEquals(200, request("GET", "/health/live", null, null, null).statusCode());
        assertEquals(200, request("GET", "/health/ready", null, null, null).statusCode());
        var specification = request("GET", "/openapi.yaml", null, null, null);
        assertEquals(200, specification.statusCode(), specification.body());
        assertTrue(specification.headers().firstValue("Content-Type").orElseThrow().startsWith("application/yaml"));
        assertTrue(specification.body().startsWith("openapi: 3.1."));
        assertTrue(specification.body().contains("/v1/transfers:"));
        assertTrue(specification.body().contains("Idempotency-Key"));
        assertTrue(specification.body().contains("/v1/admin/reconciliation:"));
        var unauthorized = request("GET", "/v1/accounts", null, null, null);
        assertEquals(401, unauthorized.statusCode());
        assertEquals("nosniff", unauthorized.headers().firstValue("X-Content-Type-Options").orElseThrow());
        assertTrue(unauthorized.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        JsonNode error = body(unauthorized);
        assertEquals(401, error.path("status").asInt());
        assertFalse(error.path("code").asText().isBlank());
        assertFalse(error.path("requestId").asText().isBlank());
        assertEquals(error.path("requestId").asText(), unauthorized.headers().firstValue("X-Request-Id").orElseThrow());
        assertFalse(unauthorized.body().contains("SQLException"));
        assertFalse(unauthorized.body().contains("password_hash"));
    }

    @Test
    void httpEnforcesAccountOwnershipAndAdminRole() throws Exception {
        var source = account(alice);
        var destination = account(bob);
        ledger.credit(admin, key(), source.id(), "100.00", "ownership fixture");
        assertEquals(404, request("GET", "/v1/accounts/" + source.id(), bobToken, null, null).statusCode());
        assertEquals(404, request("GET", "/v1/accounts/" + source.id() + "/transactions", bobToken, null, null).statusCode());
        assertEquals(403, request("POST", "/v1/transfers", bobToken,
                payment(source.id(), destination.id(), "1.00"), key()).statusCode());
        assertEquals(403, request("POST", "/v1/admin/credits", aliceToken,
                Map.of("destinationAccountId", source.id().toString(), "amount", "1.00", "reference", "forbidden"), key()).statusCode());
        assertEquals(403, request("GET", "/v1/admin/reconciliation", aliceToken, null, null).statusCode());
    }

    @Test
    void malformedDuplicateAndUnknownJsonFieldsAreRejected() throws Exception {
        for (String invalid : new String[]{"{", "{}{}", "[]", "null",
                "{\"email\":\"a@example.test\",\"email\":\"b@example.test\",\"fullName\":\"User Name\",\"password\":\"" + PASSWORD + "\"}",
                "{\"email\":\"role@example.test\",\"fullName\":\"User Name\",\"password\":\"" + PASSWORD + "\",\"role\":\"ADMIN\"}"}) {
            var response = raw("POST", "/v1/auth/register", null, invalid, "application/json", null);
            assertEquals(400, response.statusCode(), response.body());
        }
    }

    @Test
    void bodySizeAndContentTypeAreEnforced() throws Exception {
        var large = raw("POST", "/v1/auth/register", null, "{\"email\":\"" + "a".repeat(20_000) + "\"}", "application/json", null);
        assertEquals(413, large.statusCode(), large.body());
        var wrongType = raw("POST", "/v1/auth/login", null, "{}", "text/plain", null);
        assertEquals(415, wrongType.statusCode(), wrongType.body());
        assertEquals(400, raw("POST", "/v1/auth/login", null, "", "application/json", null).statusCode());
    }

    @Test
    void moneyMustBeAnExactDecimalStringAndKeyIsRequired() throws Exception {
        var source = account(alice);
        var destination = account(bob);
        ledger.credit(admin, key(), source.id(), "100.00", "decimal fixture");
        var numeric = request("POST", "/v1/transfers", aliceToken,
                Map.of("sourceAccountId", source.id().toString(), "destinationAccountId", destination.id().toString(), "amount", 1.11), key());
        assertEquals(400, numeric.statusCode(), numeric.body());
        assertEquals(400, request("POST", "/v1/transfers", aliceToken,
                payment(source.id(), destination.id(), "1.11"), null).statusCode());
        assertEquals(400, request("POST", "/v1/transfers", aliceToken,
                payment(source.id(), destination.id(), "1.001"), key()).statusCode());
        HttpRequest duplicateKey = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/transfers"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + aliceToken)
                .header("Idempotency-Key", key()).header("Idempotency-Key", key())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payment(source.id(), destination.id(), "1.00"))))
                .build();
        assertEquals(400, client.send(duplicateKey, HttpResponse.BodyHandlers.ofString()).statusCode());
        HttpRequest duplicateAuthorization = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/me"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + aliceToken)
                .header("Authorization", "Bearer " + bobToken).GET().build();
        assertEquals(401, client.send(duplicateAuthorization, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals("100.00", ledger.getAccount(alice, source.id()).balance().toPlainString());
    }

    @Test
    void idempotencyPayloadMismatchReturnsConflict() throws Exception {
        var source = account(alice);
        var destination = account(bob);
        ledger.credit(admin, key(), source.id(), "100.00", "conflict fixture");
        String key = key();
        assertEquals(201, request("POST", "/v1/transfers", aliceToken, payment(source.id(), destination.id(), "1.00"), key).statusCode());
        var conflict = request("POST", "/v1/transfers", aliceToken, payment(source.id(), destination.id(), "2.00"), key);
        assertEquals(409, conflict.statusCode(), conflict.body());
        assertEquals("IDEMPOTENCY_CONFLICT", body(conflict).path("code").asText());
        assertEquals("99.00", ledger.getAccount(alice, source.id()).balance().toPlainString());
    }

    @Test
    void malformedPathsAndPaginationReturnClientErrors() throws Exception {
        var account = account(alice);
        assertEquals(400, request("GET", "/v1/accounts/not-a-uuid", aliceToken, null, null).statusCode());
        assertEquals(400, request("GET", "/v1/accounts/" + account.id() + "/transactions?limit=not-a-number", aliceToken, null, null).statusCode());
        assertEquals(400, request("GET", "/v1/accounts/" + account.id() + "/transactions?limit=101", aliceToken, null, null).statusCode());
        assertEquals(400, request("GET", "/v1/accounts/" + account.id() + "/transactions?offset=-1", aliceToken, null, null).statusCode());
        assertEquals(400, request("GET", "/v1/accounts/" + account.id() + "/transactions?limit=2&limit=3", aliceToken, null, null).statusCode());
        assertEquals(400, request("GET", "/v1/accounts/" + account.id() + "/transactions?unknown=1", aliceToken, null, null).statusCode());
        assertEquals(404, request("GET", "/v1/unknown", aliceToken, null, null).statusCode());
        assertEquals(405, request("DELETE", "/v1/accounts", aliceToken, null, null).statusCode());
    }

    @Test
    void adminCanFreezeAndReverseThroughHttpWithoutDeletingHistory() throws Exception {
        var source = account(alice);
        var destination = account(bob);
        ledger.credit(admin, key(), source.id(), "100.00", "reversal fixture");
        var payment = request("POST", "/v1/transfers", aliceToken, payment(source.id(), destination.id(), "10.00"), key());
        assertEquals(201, payment.statusCode(), payment.body());
        String id = body(payment).path("id").asText();
        assertEquals(200, request("PATCH", "/v1/admin/accounts/" + destination.id() + "/status", adminToken,
                Map.of("status", "FROZEN"), null).statusCode());
        var reversal = request("POST", "/v1/admin/transfers/" + id + "/reversal", adminToken,
                Map.of("reason", "HTTP support refund"), key());
        assertEquals(201, reversal.statusCode(), reversal.body());
        assertEquals(id, body(reversal).path("reversalOf").asText());
        assertEquals("REVERSED", body(request("GET", "/v1/transfers/" + id, aliceToken, null, null)).path("status").asText());
        assertEquals("100.00", ledger.getAccount(alice, source.id()).balance().toPlainString());
    }

    private LedgerService.Account account(AuthService.User user) {
        return ledger.createAccount(user, "HTTP " + key().substring(0, 8), "TRY");
    }

    private Map<String, String> payment(UUID from, UUID to, String amount) {
        return Map.of("sourceAccountId", from.toString(), "destinationAccountId", to.toString(),
                "amount", amount, "description", "HTTP test");
    }

    private HttpResponse<String> request(String method, String path, String token, Object body, String key) throws Exception {
        return raw(method, path, token, body == null ? null : json.writeValueAsString(body), "application/json", key);
    }

    private HttpResponse<String> raw(String method, String path, String token, String body, String type, String key) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(15));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (key != null) builder.header("Idempotency-Key", key);
        if (body != null) builder.header("Content-Type", type);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) throws Exception {
        return json.readTree(response.body());
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
