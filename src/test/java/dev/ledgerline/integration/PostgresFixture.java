package dev.ledgerline.integration;

import dev.ledgerline.db.Database;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

/** Isolates every integration suite from existing data, even when using an external test server. */
public final class PostgresFixture implements AutoCloseable {
    private final String schema = "ledger_it_" + UUID.randomUUID().toString().replace("-", "");
    private PostgreSQLContainer<?> postgres;
    private String baseUrl;
    private String dbUser;
    private String dbPassword;
    private Database database;

    private PostgresFixture() { }

    public static PostgresFixture open() throws SQLException {
        PostgresFixture fixture = new PostgresFixture();
        try {
            fixture.start();
            return fixture;
        } catch (SQLException | RuntimeException exception) {
            try {
                fixture.close();
            } catch (Exception cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    private void start() throws SQLException {
        baseUrl = System.getenv("TEST_DATABASE_URL");
        if (baseUrl == null || baseUrl.isBlank()) {
            postgres = new PostgreSQLContainer<>("postgres:17-alpine");
            postgres.start();
            baseUrl = postgres.getJdbcUrl();
            dbUser = postgres.getUsername();
            dbPassword = postgres.getPassword();
        } else {
            if (!baseUrl.startsWith("jdbc:postgresql:")) {
                throw new IllegalArgumentException("TEST_DATABASE_URL must point to PostgreSQL");
            }
            dbUser = env("TEST_DATABASE_USER", "ledgerline");
            dbPassword = env("TEST_DATABASE_PASSWORD", "");
        }
        try (Connection connection = DriverManager.getConnection(baseUrl, dbUser, dbPassword)) {
            connection.createStatement().execute("CREATE SCHEMA " + schema);
        }
        String cleanUrl = baseUrl.replaceAll("([?&])currentSchema=[^&]*", "$1")
                .replace("?&", "?").replaceAll("[?&]$", "");
        String schemaUrl = cleanUrl + (cleanUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        database = new Database(schemaUrl, dbUser, dbPassword, 20);
        database.migrate();
    }

    public Database database() {
        return database;
    }

    @Override
    public void close() throws SQLException {
        if (database != null) database.close();
        try {
            if (baseUrl != null && schema.matches("ledger_it_[0-9a-f]{32}") && dbUser != null) {
                try (Connection connection = DriverManager.getConnection(baseUrl, dbUser, dbPassword)) {
                    connection.createStatement().execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
                }
            }
        } finally {
            if (postgres != null) postgres.stop();
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
