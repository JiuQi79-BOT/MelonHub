package com.melonhub.rbac.service.port.webhook;

import com.melonhub.rbac.service.port.common.PageResult;
import com.melonhub.rbac.service.port.webhook.WebhookModels.SaveWebhookCommand;
import com.melonhub.rbac.service.port.webhook.WebhookModels.WebhookCreatedResult;
import com.melonhub.rbac.service.port.webhook.WebhookModels.WebhookDeliveryResult;
import com.melonhub.rbac.service.port.webhook.WebhookModels.WebhookDetailResult;
import com.melonhub.rbac.service.port.webhook.WebhookModels.WebhookResult;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Webhook 订阅管理、事务外盒发信与 HMAC-SHA256 签名投递用例端口。
 */
public interface WebhookUseCase {
    List<String> EVENT_TYPES = List.of(
            "user.enabled", "user.disabled", "user.deleted", "user.roles.changed", "user.roles.expired",
            "user.updated", "user.contexts.revoked", "role.enabled", "role.disabled", "role.deleted",
            "role.permissions.changed", "role.inheritance.changed",
            "permission.updated", "permission.deleted",
            "application.enabled", "application.disabled", "apikey.revoked",
            "access.approved", "access.rejected", "review.closed");

    static String hmacSha256(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    List<WebhookResult> webhooks(long app);

    WebhookDetailResult webhook(long id);

    WebhookCreatedResult create(long app, SaveWebhookCommand body, String actor);

    void update(long id, SaveWebhookCommand body, String actor);

    void delete(long id, String actor);

    PageResult<WebhookDeliveryResult> deliveries(long id, int page, int size);

    void test(long id, String actor);

    void emit(long app, String type, Map<String, Object> data);

    void emitAll(String type, Map<String, Object> data);

    void scheduledDeliveries();

    void deliverNow();

    void stopSenders() throws InterruptedException;
}
