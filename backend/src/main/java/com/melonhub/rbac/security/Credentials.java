package com.melonhub.rbac.security;

import com.melonhub.rbac.exception.ApiException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

public final class Credentials {
    private static final SecureRandom RANDOM = new SecureRandom();
    private Credentials() {}
    public static String token() { byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return HexFormat.of().formatHex(bytes); }
    public static String apiKey() { return "mh_" + token(); }
    /** Non-secret display prefix, enough to tell keys apart in the console. */
    public static String prefix(String key) { return key.substring(0, Math.min(11, key.length())); }
    public static String hash(String value) {
        String hash = hashOrNull(value);
        if (hash == null) throw ApiException.unauthorized();
        return hash;
    }
    public static String hashOrNull(String value) {
        if (value == null || value.isBlank() || value.length() > 1000) return null;
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
