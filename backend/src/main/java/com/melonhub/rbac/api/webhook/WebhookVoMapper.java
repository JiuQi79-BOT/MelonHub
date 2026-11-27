package com.melonhub.rbac.api.webhook;

import com.melonhub.rbac.api.shared.vo.PageVO;
import com.melonhub.rbac.api.webhook.vo.WebhookCreatedVO;
import com.melonhub.rbac.api.webhook.vo.WebhookDeliveryVO;
import com.melonhub.rbac.api.webhook.vo.WebhookVO;
import com.melonhub.rbac.service.port.common.PageResult;
import com.melonhub.rbac.service.port.webhook.WebhookModels.WebhookCreatedResult;
import com.melonhub.rbac.service.port.webhook.WebhookModels.WebhookDeliveryResult;
import com.melonhub.rbac.service.port.webhook.WebhookModels.WebhookResult;

/**
 * Webhook 模块应用层结果与 HTTP VO 映射器。
 */
public final class WebhookVoMapper {
    private WebhookVoMapper() {}

    public static WebhookVO toWebhookVO(WebhookResult r) {
        return new WebhookVO(r.id(), r.name(), r.url(), r.events(), r.enabled(),
                r.createdBy(), r.createdAt(), r.pending(), r.failed(), r.lastDeliveredAt());
    }

    public static WebhookCreatedVO toWebhookCreatedVO(WebhookCreatedResult r) {
        return new WebhookCreatedVO(r.id(), r.secret());
    }

    public static PageVO<WebhookDeliveryVO> toDeliveryPageVO(PageResult<WebhookDeliveryResult> p) {
        return new PageVO<>(p.items().stream().map(d -> new WebhookDeliveryVO(
                d.id(), d.eventId(), d.eventType(), d.status(), d.attempts(),
                d.nextAttemptAt(), d.lastStatus(), d.lastError(), d.createdAt(), d.deliveredAt())).toList(),
                p.total(), p.page(), p.size());
    }
}
