package com.melonhub.client;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Webhook 签名验证：头格式 {@code MelonHub-Signature: t=<unix秒>,v1=<hex>}，
 * v1 = HMAC-SHA-256(secret, "t" + "." + rawBody) 的 hex。
 */
public final class WebhookSignatureVerifier {

    public static final long DEFAULT_TOLERANCE_SECONDS = 300;

    private WebhookSignatureVerifier() {
    }

    /** 使用默认容差（300 秒）验签。 */
    public static boolean verify(String secret, String signatureHeader, String rawBody) {
        return verify(secret, signatureHeader, rawBody, DEFAULT_TOLERANCE_SECONDS);
    }

    /** 验签：时间戳需在容差内，签名做常量时间比较。任何解析失败都返回 false。 */
    public static boolean verify(String secret, String signatureHeader, String rawBody, long toleranceSeconds) {
        Map<String, String> parts = parse(signatureHeader);
        if (parts == null || !parts.containsKey("t") || !parts.containsKey("v1")) {
            return false;
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(parts.get("t"));
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(Instant.now().getEpochSecond() - timestamp) > toleranceSeconds) {
            return false;
        }
        String expected = hmacHex(secret, parts.get("t") + "." + rawBody);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                parts.get("v1").getBytes(StandardCharsets.UTF_8));
    }

    /** 生成签名头内容，便于测试与对端调试。 */
    public static String sign(String secret, long timestamp, String body) {
        return "t=" + timestamp + ",v1=" + hmacHex(secret, timestamp + "." + body);
    }

    private static Map<String, String> parse(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        Map<String, String> parts = new LinkedHashMap<>();
        for (String pair : header.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                return null;
            }
            parts.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        return parts;
    }

    private static String hmacHex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 不可用", e);
        }
    }
}
