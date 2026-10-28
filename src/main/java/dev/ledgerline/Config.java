package dev.ledgerline;

import java.util.Map;

public record Config(String host, int port, String databaseUrl, String databaseUser,
                     String databasePassword, int poolSize, boolean migrate,
                     String adminEmail, String adminPassword, String trustedProxyIps) {
    public static Config fromEnvironment() {
        return from(System.getenv());
    }

    static Config from(Map<String, String> env) {
        String url = required(env, "DATABASE_URL");
        if (!url.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("DATABASE_URL must use jdbc:postgresql://");
        }
        String email = env.getOrDefault("BOOTSTRAP_ADMIN_EMAIL", "");
        String password = env.getOrDefault("BOOTSTRAP_ADMIN_PASSWORD", "");
        if (email.isBlank() != password.isBlank()) {
            throw new IllegalArgumentException("Provide both bootstrap admin variables, or neither");
        }
        String migrate = env.getOrDefault("DB_MIGRATE", "true");
        if (!migrate.equals("true") && !migrate.equals("false")) {
            throw new IllegalArgumentException("DB_MIGRATE must be true or false");
        }
        return new Config(env.getOrDefault("HTTP_HOST", "127.0.0.1"),
                number(env, "HTTP_PORT", 8080, 1, 65535), url,
                required(env, "DATABASE_USER"), required(env, "DATABASE_PASSWORD"),
                number(env, "DB_POOL_SIZE", 12, 2, 64), Boolean.parseBoolean(migrate), email, password,
                env.getOrDefault("TRUSTED_PROXY_IPS", ""));
    }

    private static String required(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static int number(Map<String, String> env, String key, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(env.getOrDefault(key, Integer.toString(fallback)));
            if (value < min || value > max) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
        }
    }

    @Override public String toString() {
        return "Config[host=" + host + ", port=" + port + ", secrets=REDACTED]";
    }
}
