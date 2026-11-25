package com.melonhub.rbac.service.ddd.domain.value;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.StringJoiner;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Webhook 事件白名单、HMAC-SHA256 签名与指数退避值对象。
 */
public final class WebhookSignature {
    public static final List<String> EVENT_TYPES = List.of(
            "user.enabled", "user.disabled", "user.deleted", "user.roles.changed", "user.roles.expired",
            "user.updated", "user.contexts.revoked", "role.enabled", "role.disabled", "role.deleted", "role.permissions.changed", "role.inheritance.changed",
            "permission.updated", "permission.deleted",
            "application.enabled", "application.disabled", "apikey.revoked",
            "access.approved", "access.rejected", "review.closed");

    private WebhookSignature() {}

    public static String joinValidatedEvents(List<String> events) {
        var joined = new StringJoiner(",");
        for (String event : events) {
            String trimmed = event.strip();
            if (!trimmed.equals("*") && !EVENT_TYPES.contains(trimmed)) {
                throw DomainException.bad("未知事件类型: " + trimmed + "，可用 * 或 " + String.join(", ", EVENT_TYPES));
            }
            joined.add(trimmed);
        }
        return joined.toString();
    }

    public static String header(String secret, long epochSecond, String payload) {
        return "t=" + epochSecond + ",v1=" + hmacSha256(secret, epochSecond + "." + payload);
    }

    public static String hmacSha256(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    public static int backoffMinutes(int attempt) {
        return (int) Math.min(60, 1L << (attempt - 1));
    }
}
