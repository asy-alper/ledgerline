package dev.ledgerline;

import dev.ledgerline.api.ApiServer;
import dev.ledgerline.auth.AuthService;
import dev.ledgerline.db.Database;
import dev.ledgerline.ledger.LedgerService;

import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

public final class Main {
    private Main() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 1 || (args.length == 1 && !args[0].equals("migrate"))) {
            throw new IllegalArgumentException("Usage: java -jar ledgerline.jar [migrate]");
        }
        Config config = Config.fromEnvironment();
        try (Database database = new Database(config.databaseUrl(), config.databaseUser(),
                config.databasePassword(), config.poolSize())) {
            if (config.migrate() || args.length == 1) database.migrate();
            else database.validateSchema();
            if (args.length == 1) return;
            AuthService auth = new AuthService(database);
            if (!config.adminEmail().isBlank()) auth.bootstrapAdmin(config.adminEmail(), config.adminPassword());
            try (ApiServer server = new ApiServer(config.host(), config.port(), database, auth,
                    new LedgerService(database), config.trustedProxyIps())) {
                CountDownLatch stop = new CountDownLatch(1);
                CountDownLatch cleaned = new CountDownLatch(1);
                Thread shutdown = new Thread(() -> {
                    stop.countDown();
                    try { cleaned.await(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }, "ledgerline-shutdown");
                Runtime.getRuntime().addShutdownHook(shutdown);
                server.start();
                Logger.getLogger(Main.class.getName()).info("LedgerLine listening on " + config.host() + ":" + server.port());
                try { stop.await(); }
                finally {
                    try { server.close(); }
                    finally {
                        try { database.close(); }
                        finally { cleaned.countDown(); }
                    }
                    try { Runtime.getRuntime().removeShutdownHook(shutdown); }
                    catch (IllegalStateException ignored) { /* Shutdown is already in progress. */ }
                }
            }
        }
    }
}
