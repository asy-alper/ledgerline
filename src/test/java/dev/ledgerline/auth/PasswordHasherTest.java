package dev.ledgerline.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordHasherTest {
    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void uniqueSaltsPreserveUnicodePasswordVerification() {
        String password = "Öğreniyorum🔐12345";
        String first = hasher.hash(password);
        String second = hasher.hash(password);
        assertNotEquals(first, second);
        assertTrue(hasher.verify(password, first));
        assertTrue(hasher.verify(password, second));
        assertFalse(hasher.verify("Başka-parola12345", first));
        assertTrue(first.startsWith("pbkdf2-sha256$600000$"));
        assertFalse(first.contains(password));
    }

    @Test
    void malformedHashesAndUnboundedInputsAreRejected() {
        assertFalse(hasher.verify("example-password", null));
        assertFalse(hasher.verify("example-password", "plaintext"));
        assertFalse(hasher.verify("example-password", "pbkdf2-sha256$999999999$aaaa$bbbb"));
        assertFalse(hasher.verify("example-password", "pbkdf2-sha256$1$" + "a".repeat(22) + "$" + "a".repeat(43)));
        assertFalse(hasher.verify("example-password", "pbkdf2-sha256$600000$!$!"));
        assertFalse(hasher.verify("example-password", "x".repeat(1_000_000)));
        assertFalse(hasher.verify("x".repeat(129), "pbkdf2-sha256$600000$" + "a".repeat(22) + "$" + "a".repeat(43)));
        assertFalse(hasher.verify("example-password", "pbkdf2-sha256$600000$" + "a".repeat(21) + "$" + "a".repeat(44)));
        assertFalse(hasher.verify(null, hasher.hash("example-password")));
        assertThrows(IllegalArgumentException.class, () -> hasher.hash("x".repeat(129)));
    }
}
