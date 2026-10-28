package dev.ledgerline.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.ledgerline.api.ApiException;
import org.flywaydb.core.Flyway;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

/** Owns connections and transaction boundaries; services never share a Connection across threads. */
public final class Database implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(Database.class.getName());
    private final HikariDataSource source;

    @FunctionalInterface public interface SqlWork<T> { T run(Connection connection) throws SQLException; }

    public Database(String jdbcUrl, String user, String password, int poolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5_000);
        config.setValidationTimeout(2_000);
        config.setInitializationFailTimeout(10_000);
        config.setPoolName("ledgerline-db");
        config.addDataSourceProperty("connectTimeout", "5");
        config.addDataSourceProperty("socketTimeout", "20");
        config.addDataSourceProperty("ApplicationName", "LedgerLine");
        config.setConnectionInitSql("SET statement_timeout = '10s'");
        source = new HikariDataSource(config);
    }

    public void migrate() {
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .cleanDisabled(true).baselineOnMigrate(false).validateOnMigrate(true).load().migrate();
    }

    public void validateSchema() {
        var flyway = Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .cleanDisabled(true).load();
        flyway.validate();
        if (flyway.info().current() == null || flyway.info().pending().length != 0) {
            throw new IllegalStateException("Database schema is missing or outdated; run the migrate command first");
        }
    }

    public <T> T transaction(SqlWork<T> work) {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                try (var statement = connection.createStatement()) {
                    statement.execute("SET LOCAL lock_timeout = '5s'");
                    statement.execute("SET LOCAL idle_in_transaction_session_timeout = '15s'");
                }
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException | Error error) {
                try { connection.rollback(); } catch (SQLException rollback) { error.addSuppressed(rollback); }
                throw error;
            }
        } catch (SQLException error) {
            throw translate(error);
        }
    }

    public <T> T read(SqlWork<T> work) {
        try (Connection connection = source.getConnection()) {
            connection.setReadOnly(true);
            return work.run(connection);
        } catch (SQLException error) {
            throw translate(error);
        }
    }

    public boolean ready() {
        try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.setQueryTimeout(2);
            statement.executeQuery("SELECT id FROM accounts LIMIT 1").close();
            return true;
        }
        catch (SQLException e) { return false; }
    }

    private static ApiException translate(SQLException error) {
        // SQLSTATE is safe to log. SQL messages may contain customer data or statement parameters.
        LOG.warning("Database operation failed, SQLSTATE=" + error.getSQLState());
        return new ApiException(503, "DATABASE_UNAVAILABLE",
                "İşlem tamamlanamadı. Para hareketlerinde aynı Idempotency-Key ile tekrar deneyin.");
    }

    @Override public void close() { source.close(); }
}
