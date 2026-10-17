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
    public static String hash(String value) {
        if (value == null || value.isBlank() || value.length() > 1000) throw ApiException.unauthorized();
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
