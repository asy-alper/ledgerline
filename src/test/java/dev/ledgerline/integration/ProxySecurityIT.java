package dev.ledgerline.integration;

import dev.ledgerline.api.ApiServer;
import dev.ledgerline.api.Json;
import dev.ledgerline.auth.AuthService;
import dev.ledgerline.db.Database;
import dev.ledgerline.ledger.LedgerService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Actual HTTP requests prove that untrusted headers cannot choose authentication rate-limit buckets. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProxySecurityIT {
    private static final String PASSWORD = "Proxy integration! 2026-password";
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private PostgresFixture postgres;
    private Database database;
    private AuthService auth;
    private LedgerService ledger;
    private String token;
    private String email;

    @BeforeAll
    void setUp() throws Exception {
        postgres = PostgresFixture.open();
        database = postgres.database();
        auth = new AuthService(database);
        ledger = new LedgerService(database);
        email = "proxy-member@example.test";
        auth.register(email, "Proxy Member", PASSWORD, "proxy-test-fixture");
        token = auth.login(email, PASSWORD, "proxy-test-fixture").token();
    }

    @AfterAll
    void tearDown() throws Exception {
        client.close();
        if (postgres != null) postgres.close();
    }

    @Test
    void defaultServerIgnoresSpoofedClientIpHeaders() throws Exception {
        long directBefore = attempts("127.0.0.1");
        try (ApiServer server = defaultServer()) {
            var response = login(server, "X-Real-IP", "192.0.2.10", "X-Forwarded-For", "192.0.2.11");

            assertEquals(401, response.statusCode(), response.body());
            assertEquals(directBefore + 1, attempts("127.0.0.1"));
            assertEquals(0, attempts("192.0.2.10"));
            assertEquals(0, attempts("192.0.2.11"));
        }
    }

    @Test
    void allowlistDoesNotTrustADifferentSocketPeer() throws Exception {
        long directBefore = attempts("127.0.0.1");
        try (ApiServer server = server("192.0.2.90")) {
            // An invalid header is deliberately ignored when its sender is not a trusted proxy.
            var response = login(server, "X-Real-IP", "spoofed.invalid.example");

            assertEquals(401, response.statusCode(), response.body());
            assertEquals(directBefore + 1, attempts("127.0.0.1"));
            assertEquals(0, attempts("spoofed.invalid.example"));
        }
    }

    @Test
    void trustedProxyUsesOnlyItsSingleRealIpHeaderAndSeparatesClientBuckets() throws Exception {
        long directBefore = attempts("127.0.0.1");
        try (ApiServer server = server("127.0.0.1")) {
            assertEquals(401, login(server, "X-Real-IP", "198.51.100.7",
                    "X-Forwarded-For", "203.0.113.99").statusCode());
            assertEquals(401, login(server, "X-Real-IP", "198.51.100.8").statusCode());

            assertEquals(1, attempts("198.51.100.7"));
            assertEquals(1, attempts("198.51.100.8"));
            assertEquals(0, attempts("203.0.113.99"));
            assertEquals(directBefore, attempts("127.0.0.1"));
        }
    }

    @Test
    void equivalentIpv6AddressesShareOneNormalizedClientBucket() throws Exception {
        String compressed = "2001:db8::25";
        String expanded = "2001:0db8:0000:0000:0000:0000:0000:0025";
        String canonical = InetAddress.getByName(compressed).getHostAddress();
        try (ApiServer server = server("127.0.0.1, ::1")) {
            assertEquals(401, login(server, "X-Real-IP", compressed).statusCode());
            assertEquals(401, login(server, "X-Real-IP", expanded).statusCode());

            assertEquals(2, attempts(canonical));
        }
    }

    @Test
    void trustedProxyRequiresAValidIpLiteralBeforeAuthenticationIsAttempted() throws Exception {
        long bucketsBefore = bucketCount();
        try (ApiServer server = server("127.0.0.1")) {
            assertInvalidClientIp(login(server));
            for (String invalid : List.of("localhost", "deadbeef", "127.1", "256.1.1.1",
                    "192.168.001.1", "203.0.113.1, 198.51.100.1", "[::1]", "::1%zone", "1.2.3.4:80")) {
                assertInvalidClientIp(login(server, "X-Real-IP", invalid));
            }

            assertEquals(bucketsBefore, bucketCount(), "Rejected proxy metadata must not consume or create auth buckets");
        }
    }

    @Test
    void trustedProxyRejectsDuplicateRealIpHeaders() throws Exception {
        long bucketsBefore = bucketCount();
        try (ApiServer server = server("127.0.0.1")) {
            assertInvalidClientIp(login(server, "X-Real-IP", "203.0.113.10", "X-Real-IP", "203.0.113.11"));

            assertEquals(bucketsBefore, bucketCount());
            assertEquals(0, attempts("203.0.113.10"));
            assertEquals(0, attempts("203.0.113.11"));
        }
    }

    @Test
    void proxyConfigurationRejectsHostnamesNetworksAndAmbiguousIpSpellings() {
        for (String invalid : List.of("localhost", "example.com", "deadbeef", "127.0.0.1/32",
                "127.1", "300.1.1.1", "127.00.0.1", "[::1]", "::1%1", "127.0.0.1,", ",127.0.0.1")) {
            assertThrows(IllegalArgumentException.class, () -> {
                // Closing on unexpected success prevents this negative test from leaking a bound socket.
                try (ApiServer ignored = new ApiServer("127.0.0.1", 0, database, auth, ledger, invalid)) {
                    fail("Accepted invalid trusted proxy configuration: " + invalid);
                }
            }, invalid);
        }
    }

    @Test
    void bearerSchemeIsCaseInsensitiveWithoutChangingTheToken() throws Exception {
        try (ApiServer server = defaultServer()) {
            for (String scheme : List.of("bearer", "BEARER", "bEaReR")) {
                HttpRequest request = HttpRequest.newBuilder(uri(server, "/v1/me"))
                        .timeout(Duration.ofSeconds(10)).header("Authorization", scheme + " " + token).GET().build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());

                assertEquals(200, response.statusCode(), response.body());
                assertEquals(email, Json.MAPPER.readTree(response.body()).path("email").asText());
            }
        }
    }

    private ApiServer defaultServer() throws Exception {
        ApiServer server = new ApiServer("127.0.0.1", 0, database, auth, ledger);
        server.start();
        return server;
    }

    private ApiServer server(String trustedProxies) throws Exception {
        ApiServer server = new ApiServer("127.0.0.1", 0, database, auth, ledger, trustedProxies);
        server.start();
        return server;
    }

    private HttpResponse<String> login(ApiServer server, String... headers) throws Exception {
        String body = Json.MAPPER.writeValueAsString(Map.of("email", "unknown-" + UUID.randomUUID() + "@example.test",
                "password", PASSWORD));
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(server, "/v1/auth/login"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        for (int index = 0; index < headers.length; index += 2) builder.header(headers[index], headers[index + 1]);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(ApiServer server, String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }

    private static void assertInvalidClientIp(HttpResponse<String> response) throws Exception {
        assertEquals(400, response.statusCode(), response.body());
        assertEquals("INVALID_CLIENT_IP", Json.MAPPER.readTree(response.body()).path("code").asText());
    }

    private long attempts(String ip) throws Exception {
        String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(("login:client:" + ip).getBytes(StandardCharsets.UTF_8)));
        return database.read(connection -> {
            try (var statement = connection.prepareStatement("SELECT coalesce(max(attempts), 0) FROM auth_rate_limits WHERE bucket_key = ?")) {
                statement.setString(1, key);
                try (var result = statement.executeQuery()) {
                    result.next();
                    return result.getLong(1);
                }
            }
        });
    }

    private long bucketCount() {
        return database.read(connection -> {
            try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT count(*) FROM auth_rate_limits")) {
                result.next();
                return result.getLong(1);
            }
        });
    }
}
