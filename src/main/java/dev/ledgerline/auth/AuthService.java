package dev.ledgerline.auth;

import dev.ledgerline.api.ApiException;
import dev.ledgerline.db.Database;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Authentication uses revocable opaque tokens; raw tokens never enter the database. */
public final class AuthService {
    public record User(UUID id, String email, String fullName, String role) { }
    public record Session(String token, Instant expiresAt, User user) { }

    private record Credentials(User user, String passwordHash) { }
    private static final Pattern EMAIL = Pattern.compile(
            "^[a-z0-9][a-z0-9._%+\\-]{0,63}@[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
                    + "(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$");
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Database database;
    private final PasswordHasher hasher = new PasswordHasher();
    private final String dummyHash = hasher.hash("Random-unused-" + UUID.randomUUID());

    public AuthService(Database database) {
        this.database = database;
    }

    public User register(String email, String fullName, String password) {
        return register(email, fullName, password, "local");
    }

    public User register(String email, String fullName, String password, String clientId) {
        String normalized = normalizeEmail(email);
        requireEmail(normalized);
        String name = validateName(fullName);
        validatePassword(password);
        consumeLimits("register", normalized, clientId, 5, 30);
        String encoded = hasher.hash(password);
        return database.transaction(connection -> {
            User user = new User(UUID.randomUUID(), normalized, name, "USER");
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO users(id, email, full_name, password_hash, role)
                    VALUES (?, ?, ?, ?, 'USER') ON CONFLICT (email) DO NOTHING
                    """)) {
                statement.setObject(1, user.id());
                statement.setString(2, normalized);
                statement.setString(3, name);
                statement.setString(4, encoded);
                if (statement.executeUpdate() != 1) {
                    throw new ApiException(409, "EMAIL_ALREADY_REGISTERED", "Bu e-posta ile bir hesap zaten var.");
                }
            }
            return user;
        });
    }

    public Session login(String email, String password, String clientId) {
        String normalized = normalizeEmail(email);
        consumeLimits("login", normalized, clientId, 10, 60);
        Credentials credentials = database.read(connection -> findCredentials(connection, normalized));
        // A missing user incurs the same password derivation as an incorrect password.
        String candidate = password == null || password.length() > 128 ? "invalid-password" : password;
        boolean matches = hasher.verify(candidate, credentials == null ? dummyHash : credentials.passwordHash());
        if (credentials == null || password == null || password.length() > 128 || !matches) {
            throw new ApiException(401, "INVALID_CREDENTIALS", "E-posta veya parola hatalı.");
        }
        byte[] randomBytes = new byte[32];
        RANDOM.nextBytes(randomBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        String tokenHash = sha256(token);
        return database.transaction(connection -> {
            Instant expiresAt;
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO sessions(token_hash, user_id, expires_at)
                    VALUES (?, ?, now() + interval '1 hour') RETURNING expires_at
                    """)) {
                statement.setString(1, tokenHash);
                statement.setObject(2, credentials.user().id());
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    expiresAt = result.getTimestamp("expires_at").toInstant();
                }
            }
            return new Session(token, expiresAt, credentials.user());
        });
    }

    public User authenticate(String rawToken) {
        requireToken(rawToken);
        return database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT u.id, u.email, u.full_name, u.role
                    FROM sessions s JOIN users u ON u.id = s.user_id
                    WHERE s.token_hash = ? AND s.expires_at > now()
                    """)) {
                statement.setString(1, sha256(rawToken));
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw unauthorized();
                    }
                    return userFrom(result);
                }
            }
        });
    }

    public void logout(String rawToken) {
        requireToken(rawToken);
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM sessions WHERE token_hash = ?")) {
                statement.setString(1, sha256(rawToken));
                statement.executeUpdate();
            }
            return null;
        });
    }

    /** Startup-only provisioning. An existing USER account can never become ADMIN through bootstrap. */
    public void bootstrapAdmin(String email, String password) {
        String normalized = normalizeEmail(email);
        requireEmail(normalized);
        validatePassword(password);
        String encoded = hasher.hash(password);
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO users(id, email, full_name, password_hash, role)
                    VALUES (?, ?, 'Administrator', ?, 'ADMIN') ON CONFLICT (email) DO NOTHING
                    """)) {
                statement.setObject(1, UUID.randomUUID());
                statement.setString(2, normalized);
                statement.setString(3, encoded);
                statement.executeUpdate();
            }
            Credentials existing = findCredentials(connection, normalized);
            if (existing == null || !"ADMIN".equals(existing.user().role())) {
                throw new ApiException(409, "ADMIN_BOOTSTRAP_CONFLICT",
                        "Yönetici e-postası mevcut bir kullanıcıya ait; otomatik yetki yükseltilmedi.");
            }
            return null;
        });
    }

    private void consumeLimits(String namespace, String email, String clientId, int emailLimit, int clientLimit) {
        String client = clientId == null || clientId.isBlank() ? "unknown" : clientId;
        if (client.length() > 200) {
            client = client.substring(0, 200);
        }
        String emailBucket = sha256(namespace + ":email:" + email);
        String clientBucket = sha256(namespace + ":client:" + client);
        // The counter must commit before a later authentication failure is thrown.
        boolean allowed = database.transaction(connection -> {
            int clientAttempts = increment(connection, clientBucket);
            // A blocked source cannot grow the table by submitting arbitrary new email addresses.
            int emailAttempts = clientAttempts <= clientLimit ? increment(connection, emailBucket) : emailLimit + 1;
            cleanup(connection);
            return clientAttempts <= clientLimit && emailAttempts <= emailLimit;
        });
        if (!allowed) {
            throw new ApiException(429, "AUTH_RATE_LIMITED", "Çok fazla deneme. En geç 15 dakika sonra tekrar deneyin.");
        }
    }

    private static int increment(Connection connection, String bucket) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO auth_rate_limits(bucket_key, window_start, attempts) VALUES (?, now(), 1)
                ON CONFLICT (bucket_key) DO UPDATE SET
                    attempts = CASE WHEN auth_rate_limits.window_start <= now() - interval '15 minutes'
                        THEN 1 ELSE LEAST(auth_rate_limits.attempts + 1, 100000) END,
                    window_start = CASE WHEN auth_rate_limits.window_start <= now() - interval '15 minutes'
                        THEN now() ELSE auth_rate_limits.window_start END
                RETURNING attempts
                """)) {
            statement.setString(1, bucket);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt("attempts");
            }
        }
    }

    private static void cleanup(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM auth_rate_limits WHERE bucket_key IN (
                    SELECT bucket_key FROM auth_rate_limits
                    WHERE window_start < now() - interval '30 minutes'
                    LIMIT 200 FOR UPDATE SKIP LOCKED)
                """)) {
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM sessions WHERE token_hash IN (
                    SELECT token_hash FROM sessions WHERE expires_at < now()
                    LIMIT 200 FOR UPDATE SKIP LOCKED)
                """)) {
            statement.executeUpdate();
        }
    }

    private static Credentials findCredentials(Connection connection, String email) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, email, full_name, role, password_hash FROM users WHERE email = ?")) {
            statement.setString(1, email);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new Credentials(userFrom(result), result.getString("password_hash")) : null;
            }
        }
    }

    private static User userFrom(ResultSet result) throws SQLException {
        return new User(result.getObject("id", UUID.class), result.getString("email"),
                result.getString("full_name"), result.getString("role"));
    }

    private static String normalizeEmail(String email) {
        if (email == null) {
            return "";
        }
        if (email.length() > 300) {
            throw new ApiException(400, "INVALID_EMAIL", "Geçerli bir e-posta girin (en çok 254 karakter).");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static void requireEmail(String email) {
        if (email.length() > 254 || !EMAIL.matcher(email).matches() || email.contains("..")) {
            throw new ApiException(400, "INVALID_EMAIL", "Geçerli bir e-posta girin (en çok 254 karakter).");
        }
    }

    private static String validateName(String fullName) {
        String name = fullName == null ? "" : fullName.trim();
        if (name.length() < 2 || name.length() > 100 || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new ApiException(400, "INVALID_NAME", "Ad soyad 2–100 karakter olmalıdır.");
        }
        return name;
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 128
                || password.isBlank()) {
            throw new ApiException(400, "INVALID_PASSWORD", "Parola 12–128 karakter olmalıdır.");
        }
    }

    private static void requireToken(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            throw unauthorized();
        }
    }

    private static ApiException unauthorized() {
        return new ApiException(401, "UNAUTHORIZED", "Geçerli bir oturum gerekli.");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailableAlgorithm) {
            throw new IllegalStateException("Required JDK digest is unavailable", unavailableAlgorithm);
        }
    }
}
