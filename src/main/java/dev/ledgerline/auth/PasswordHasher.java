package dev.ledgerline.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** Passwords use a deliberately expensive, salted one-way derivation supplied by the JDK. */
public final class PasswordHasher {
    public static final int ITERATIONS = 600_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    public String hash(String password) {
        if (password == null || password.length() > 128) {
            throw new IllegalArgumentException("Password must contain at most 128 characters");
        }
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] derived = derive(password, salt, ITERATIONS);
        try {
            return "pbkdf2-sha256$" + ITERATIONS + "$" + ENCODER.encodeToString(salt)
                    + "$" + ENCODER.encodeToString(derived);
        } finally {
            Arrays.fill(derived, (byte) 0);
        }
    }

    public boolean verify(String password, String encoded) {
        if (password == null || password.length() > 128 || encoded == null || encoded.length() > 160) {
            return false;
        }
        try {
            String[] parts = encoded.split("\\$", -1);
            if (parts.length != 4 || !parts[0].equals("pbkdf2-sha256")) {
                return false;
            }
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < ITERATIONS || iterations > 2_000_000) {
                return false;
            }
            if (parts[2].length() != 22 || parts[3].length() != 43) {
                return false;
            }
            byte[] salt = DECODER.decode(parts[2]);
            byte[] expected = DECODER.decode(parts[3]);
            if (salt.length != 16 || expected.length != 32) {
                return false;
            }
            byte[] actual = derive(password, salt, iterations);
            try {
                return MessageDigest.isEqual(expected, actual);
            } finally {
                Arrays.fill(actual, (byte) 0);
                Arrays.fill(expected, (byte) 0);
            }
        } catch (IllegalArgumentException malformedHash) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        char[] characters = password.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(characters, salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException unavailableAlgorithm) {
            throw new IllegalStateException("Required JDK password algorithm is unavailable", unavailableAlgorithm);
        } finally {
            spec.clearPassword();
            Arrays.fill(characters, '\0');
        }
    }
}
