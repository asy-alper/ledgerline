package dev.ledgerline.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.ledgerline.auth.AuthService;
import dev.ledgerline.auth.AuthService.User;
import dev.ledgerline.db.Database;
import dev.ledgerline.ledger.LedgerService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/** Small HTTP adapter: transport validation here, business authorization in services. */
public final class ApiServer implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(ApiServer.class.getName());
    private static final int MAX_BODY = 16_384;
    private final HttpServer server;
    private final ThreadPoolExecutor executor;
    private final Database database;
    private final AuthService auth;
    private final LedgerService ledger;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<String> trustedProxies;

    public ApiServer(String host, int port, Database database, AuthService auth, LedgerService ledger) throws IOException {
        this(host, port, database, auth, ledger, "");
    }

    public ApiServer(String host, int port, Database database, AuthService auth, LedgerService ledger,
                     String trustedProxyIps) throws IOException {
        this.database = database;
        this.auth = auth;
        this.ledger = ledger;
        Set<String> proxies = new HashSet<>();
        if (trustedProxyIps != null && !trustedProxyIps.isBlank()) {
            for (String ip : trustedProxyIps.split(",", -1)) proxies.add(ipLiteral(ip.strip()));
        }
        trustedProxies = Set.copyOf(proxies);
        System.setProperty("sun.net.httpserver.maxReqTime", "15");
        System.setProperty("sun.net.httpserver.maxRspTime", "20");
        System.setProperty("sun.net.httpserver.idleInterval", "30");
        System.setProperty("sun.net.httpserver.maxReqHeaders", "64");
        server = HttpServer.create(new InetSocketAddress(host, port), 128);
        executor = new ThreadPoolExecutor(8, 32, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128));
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }

    private void handle(HttpExchange exchange) {
        String requestId = UUID.randomUUID().toString();
        var headers = exchange.getResponseHeaders();
        headers.set("X-Request-Id", requestId);
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Cache-Control", "no-store");
        headers.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        try {
            if (exchange.getRequestURI().toASCIIString().length() > 2048) {
                throw new ApiException(414, "URI_TOO_LONG", "İstek adresi çok uzun.");
            }
            route(exchange);
        } catch (ApiException error) {
            if (error.status() == 401) headers.set("WWW-Authenticate", "Bearer");
            if (error.status() == 429) headers.set("Retry-After", "900");
            problem(exchange, error.status(), error.code(), error.getMessage(), requestId);
        } catch (IOException error) {
            LOG.fine("Client connection ended, requestId=" + requestId);
        } catch (Exception error) {
            // Do not log request bodies, authentication headers, SQL text or customer fields.
            LOG.severe("Unhandled " + error.getClass().getSimpleName() + ", requestId=" + requestId);
            problem(exchange, 500, "INTERNAL_ERROR", "Beklenmeyen bir hata oluştu.", requestId);
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange x) throws IOException {
        String path = x.getRequestURI().getRawPath();
        if (path.equals("/health/live")) { method(x, "GET"); send(x, 200, Map.of("status", "UP")); return; }
        if (path.equals("/health/ready")) {
            method(x, "GET"); boolean ready = database.ready();
            send(x, ready ? 200 : 503, Map.of("status", ready ? "UP" : "DOWN")); return;
        }
        if (path.equals("/openapi.yaml")) {
            method(x, "GET");
            try (var resource = ApiServer.class.getResourceAsStream("/openapi.yaml")) {
                if (resource == null) throw new IllegalStateException("Missing OpenAPI resource");
                byte[] bytes = resource.readAllBytes();
                x.getResponseHeaders().set("Content-Type", "application/yaml; charset=utf-8");
                x.sendResponseHeaders(200, bytes.length); x.getResponseBody().write(bytes);
            }
            return;
        }
        if (path.equals("/v1/auth/register")) {
            method(x, "POST"); JsonNode b = body(x, "email", "fullName", "password");
            send(x, 201, auth.register(text(b, "email"), text(b, "fullName"), text(b, "password"), peer(x))); return;
        }
        if (path.equals("/v1/auth/login")) {
            method(x, "POST"); JsonNode b = body(x, "email", "password");
            send(x, 200, auth.login(text(b, "email"), text(b, "password"), peer(x))); return;
        }
        if (!path.startsWith("/v1/")) throw missing();
        String token = bearer(x);
        User user = auth.authenticate(token);
        if (path.equals("/v1/auth/logout")) { method(x, "POST"); auth.logout(token); send(x, 204, null); return; }
        if (path.equals("/v1/me")) { method(x, "GET"); send(x, 200, user); return; }
        if (path.equals("/v1/accounts")) {
            if (x.getRequestMethod().equals("GET")) { send(x, 200, ledger.listAccounts(user)); return; }
            method(x, "POST", "GET"); JsonNode b = body(x, "label", "currency");
            send(x, 201, ledger.createAccount(user, text(b, "label"), text(b, "currency"))); return;
        }
        String[] parts = path.split("/", -1);
        if (parts.length == 4 && parts[2].equals("accounts")) {
            method(x, "GET"); send(x, 200, ledger.getAccount(user, uuid(parts[3]))); return;
        }
        if (parts.length == 5 && parts[2].equals("accounts") && parts[4].equals("transactions")) {
            method(x, "GET"); Map<String, String> query = query(x, Set.of("limit", "offset"));
            send(x, 200, ledger.history(user, uuid(parts[3]), integer(query, "limit", 20), integer(query, "offset", 0))); return;
        }
        if (path.equals("/v1/transfers")) {
            method(x, "POST"); JsonNode b = body(x, "sourceAccountId", "destinationAccountId", "amount", "description");
            movement(x, ledger.transfer(user, key(x), uuid(text(b, "sourceAccountId")), uuid(text(b, "destinationAccountId")),
                    text(b, "amount"), optionalText(b, "description"))); return;
        }
        if (parts.length == 4 && parts[2].equals("transfers")) {
            method(x, "GET"); send(x, 200, ledger.getTransfer(user, uuid(parts[3]))); return;
        }
        if (path.equals("/v1/admin/credits")) {
            method(x, "POST"); JsonNode b = body(x, "destinationAccountId", "amount", "reference");
            movement(x, ledger.credit(user, key(x), uuid(text(b, "destinationAccountId")), text(b, "amount"), text(b, "reference"))); return;
        }
        if (parts.length == 6 && parts[2].equals("admin") && parts[3].equals("transfers") && parts[5].equals("reversal")) {
            method(x, "POST"); JsonNode b = body(x, "reason");
            movement(x, ledger.reverse(user, key(x), uuid(parts[4]), text(b, "reason"))); return;
        }
        if (parts.length == 6 && parts[2].equals("admin") && parts[3].equals("accounts") && parts[5].equals("status")) {
            method(x, "PATCH"); JsonNode b = body(x, "status");
            send(x, 200, ledger.setAccountStatus(user, uuid(parts[4]), text(b, "status"))); return;
        }
        if (path.equals("/v1/admin/reconciliation")) { method(x, "GET"); send(x, 200, ledger.reconcile(user)); return; }
        throw missing();
    }

    private String peer(HttpExchange x) {
        String direct = x.getRemoteAddress().getAddress().getHostAddress();
        if (!trustedProxies.contains(direct)) return direct;
        var values = x.getRequestHeaders().get("X-Real-IP");
        if (values == null || values.size() != 1) throw new ApiException(400, "INVALID_CLIENT_IP", "Proxy istemci IP adresini göndermeli.");
        try { return ipLiteral(values.getFirst()); }
        catch (IllegalArgumentException e) { throw new ApiException(400, "INVALID_CLIENT_IP", "Geçersiz istemci IP adresi."); }
    }

    private static String ipLiteral(String value) {
        if (value == null || value.length() > 45 || !value.matches("[0-9a-fA-F:.]+")) throw new IllegalArgumentException("Use literal proxy IPs");
        if (!value.contains(":")) {
            if (!value.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) throw new IllegalArgumentException("Invalid IPv4");
            for (String octet : value.split("\\.")) if (Integer.parseInt(octet) > 255) throw new IllegalArgumentException("Invalid IPv4");
        }
        try { return InetAddress.getByName(value).getHostAddress(); }
        catch (IOException e) { throw new IllegalArgumentException("Invalid IP literal", e); }
    }

    private static JsonNode body(HttpExchange x, String... allowedFields) throws IOException {
        String type = x.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
            throw new ApiException(415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type application/json olmalı.");
        }
        byte[] bytes = x.getRequestBody().readNBytes(MAX_BODY + 1);
        if (bytes.length > MAX_BODY) throw new ApiException(413, "BODY_TOO_LARGE", "İstek gövdesi en fazla 16 KiB olabilir.");
        try (var parser = Json.MAPPER.createParser(bytes)) {
            JsonNode node = Json.MAPPER.readTree(parser);
            if (node == null || !node.isObject() || parser.nextToken() != null) throw new IOException("Object required");
            Set<String> allowed = Set.of(allowedFields);
            var names = node.fieldNames();
            while (names.hasNext()) if (!allowed.contains(names.next())) throw new IOException("Unknown field");
            return node;
        } catch (IOException error) {
            throw new ApiException(400, "INVALID_JSON", "Geçerli bir JSON nesnesi ve yalnızca tanımlanan alanları gönderin.");
        }
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isTextual()) throw new ApiException(400, "INVALID_FIELD", field + " metin olarak gönderilmeli.");
        return value.textValue();
    }

    private static String optionalText(JsonNode body, String field) {
        return !body.has(field) ? "" : text(body, field);
    }

    private static UUID uuid(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException e) { throw new ApiException(400, "INVALID_ID", "Geçerli bir UUID gönderin."); }
    }

    private static String key(HttpExchange x) {
        var values = x.getRequestHeaders().get("Idempotency-Key");
        if (values == null || values.size() != 1 || !values.getFirst().matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new ApiException(400, "INVALID_IDEMPOTENCY_KEY", "Idempotency-Key 8–128 ASCII harf, rakam veya ._:- içermeli.");
        }
        return values.getFirst();
    }

    private static String bearer(HttpExchange x) {
        var values = x.getRequestHeaders().get("Authorization");
        if (values == null || values.size() != 1 || !values.getFirst().regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new ApiException(401, "UNAUTHORIZED", "Bearer oturum anahtarı gerekli.");
        }
        return values.getFirst().substring(7);
    }

    private static void method(HttpExchange x, String... allowed) {
        if (!Set.of(allowed).contains(x.getRequestMethod())) {
            x.getResponseHeaders().set("Allow", String.join(", ", allowed));
            throw new ApiException(405, "METHOD_NOT_ALLOWED", "Bu adreste HTTP yöntemi desteklenmiyor.");
        }
    }

    private static Map<String, String> query(HttpExchange x, Set<String> allowed) {
        Map<String, String> result = new HashMap<>();
        String raw = x.getRequestURI().getRawQuery();
        if (raw == null) return result;
        try {
            for (String pair : raw.split("&", -1)) {
                String[] kv = pair.split("=", 2);
                String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
                String value = kv.length == 2 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
                if (!allowed.contains(key) || result.putIfAbsent(key, value) != null) throw new IllegalArgumentException();
            }
            return result;
        } catch (IllegalArgumentException e) { throw new ApiException(400, "INVALID_QUERY", "Geçersiz veya tekrarlanan sorgu parametresi."); }
    }

    private static int integer(Map<String, String> query, String key, int fallback) {
        try { return Integer.parseInt(query.getOrDefault(key, Integer.toString(fallback))); }
        catch (NumberFormatException e) { throw new ApiException(400, "INVALID_QUERY", key + " tamsayı olmalı."); }
    }

    private static ApiException missing() { return new ApiException(404, "NOT_FOUND", "Adres bulunamadı."); }

    private static void movement(HttpExchange x, LedgerService.Mutation result) throws IOException {
        x.getResponseHeaders().set("Idempotent-Replayed", Boolean.toString(result.replayed()));
        x.getResponseHeaders().set("Location", "/v1/transfers/" + result.transfer().id());
        send(x, result.replayed() ? 200 : 201, result.transfer());
    }

    private static void problem(HttpExchange x, int status, String code, String detail, String requestId) {
        try {
            x.getResponseHeaders().set("Content-Type", "application/problem+json; charset=utf-8");
            send(x, status, Map.of("type", "about:blank", "title", code, "status", status,
                    "code", code, "detail", detail, "requestId", requestId));
        } catch (IOException ignored) { /* Client may have disconnected. */ }
    }

    private static void send(HttpExchange x, int status, Object value) throws IOException {
        if (status == 204) { x.sendResponseHeaders(status, -1); return; }
        byte[] bytes = Json.MAPPER.writeValueAsBytes(value);
        if (!x.getResponseHeaders().containsKey("Content-Type")) x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        x.sendResponseHeaders(status, bytes.length);
        x.getResponseBody().write(bytes);
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        server.stop(3);
        executor.shutdown();
        try { if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow(); }
        catch (InterruptedException e) { executor.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
