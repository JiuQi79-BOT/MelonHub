package com.melonhub.rbac.security;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238 TOTP (HMAC-SHA-1, 30 s steps, 6 digits) with Base32 secrets; no external dependency. */
public final class Totp {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    public static final int STEP_SECONDS = 30;

    private Totp() {}

    public static String newSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        StringBuilder out = new StringBuilder(32);
        int buffer = 0, bits = 0;
        for (byte b : bytes) {
            buffer = buffer << 8 | b & 0xFF;
            bits += 8;
            while (bits >= 5) { out.append(ALPHABET.charAt(buffer >> bits - 5 & 31)); bits -= 5; }
        }
        if (bits > 0) out.append(ALPHABET.charAt(buffer << 5 - bits & 31));
        return out.toString();
    }

    public static long currentStep() { return Instant.now().getEpochSecond() / STEP_SECONDS; }

    /** Returns the matched step when the code verifies, tolerating one step of clock drift either way. */
    public static Long verify(String secret, String code) {
        if (secret == null || code == null || !code.matches("\\d{6}")) return null;
        byte[] key;
        try { key = decode(secret); } catch (RuntimeException e) { return null; }
        long now = currentStep();
        for (long candidate : new long[]{now, now - 1, now + 1}) {
            byte[] expected = codeAt(key, candidate).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(expected, code.getBytes(java.nio.charset.StandardCharsets.UTF_8))) return candidate;
        }
        return null;
    }

    public static String codeAt(String secret, long step) { return codeAt(decode(secret), step); }

    private static String codeAt(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] message = new byte[8];
            for (int i = 7; i >= 0; i--) { message[i] = (byte) (step & 0xFF); step >>= 8; }
            byte[] hash = mac.doFinal(message);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = (hash[offset] & 0x7F) << 24 | (hash[offset + 1] & 0xFF) << 16 | (hash[offset + 2] & 0xFF) << 8 | hash[offset + 3] & 0xFF;
            return String.format("%06d", binary % 1_000_000);
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    private static byte[] decode(String secret) {
        String clean = secret.strip().replace(" ", "").replace("=", "").toUpperCase(java.util.Locale.ROOT);
        var out = new java.io.ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (char c : clean.toCharArray()) {
            int value = ALPHABET.indexOf(c);
            if (value < 0) throw new IllegalArgumentException("invalid base32");
            buffer = buffer << 5 | value;
            bits += 5;
            if (bits >= 8) { out.write(buffer >> bits - 8 & 0xFF); bits -= 8; }
        }
        return out.toByteArray();
    }
}
