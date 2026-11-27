package com.melonhub.rbac.api.webhook;

import static com.melonhub.rbac.service.port.security.AdminFunction.APPLICATION_CREDENTIALS;
import static com.melonhub.rbac.service.port.security.AdminFunction.DOMAIN_VIEW;

import com.melonhub.rbac.api.shared.vo.OkVO;
import com.melonhub.rbac.api.shared.vo.PageVO;
import com.melonhub.rbac.api.webhook.dto.WebhookDTO;
import com.melonhub.rbac.api.webhook.vo.WebhookCreatedVO;
import com.melonhub.rbac.api.webhook.vo.WebhookDeliveryVO;
import com.melonhub.rbac.api.webhook.vo.WebhookVO;
import com.melonhub.rbac.infrastructure.security.AdminAccess;
import com.melonhub.rbac.infrastructure.security.RequiresFunction;
import com.melonhub.rbac.service.port.webhook.WebhookModels.SaveWebhookCommand;
import com.melonhub.rbac.service.port.webhook.WebhookUseCase;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台 Webhook 订阅与投递记录管理接口。
 */
@RestController
@RequestMapping("/api/admin")
public class WebhookController {
    private final WebhookUseCase webhooks;

    public WebhookController(WebhookUseCase webhooks) {
        this.webhooks = webhooks;
    }

    @RequiresFunction(DOMAIN_VIEW)
    @GetMapping("/applications/{id}/webhooks")
    public List<WebhookVO> list(@PathVariable long id, HttpServletRequest req) {
        AdminAccess.application(req, id);
        return webhooks.webhooks(id).stream().map(WebhookVoMapper::toWebhookVO).toList();
    }

    @RequiresFunction(APPLICATION_CREDENTIALS)
    @PostMapping("/applications/{id}/webhooks")
    public WebhookCreatedVO create(@PathVariable long id, @Valid @RequestBody WebhookDTO body, HttpServletRequest req) {
        return WebhookVoMapper.toWebhookCreatedVO(webhooks.create(id,
                new SaveWebhookCommand(body.name(), body.url(), body.secret(), body.events(), body.enabled()),
                AdminAccess.application(req, id).actor()));
    }

    @RequiresFunction(APPLICATION_CREDENTIALS)
    @PutMapping("/webhooks/{id}")
    public OkVO update(@PathVariable long id, @Valid @RequestBody WebhookDTO body, HttpServletRequest req) {
        AdminAccess.application(req, webhooks.webhook(id).applicationId());
        webhooks.update(id, new SaveWebhookCommand(body.name(), body.url(), body.secret(), body.events(), body.enabled()),
                AdminAccess.of(req).actor());
        return OkVO.OK;
    }

    @RequiresFunction(APPLICATION_CREDENTIALS)
    @DeleteMapping("/webhooks/{id}")
    public OkVO delete(@PathVariable long id, HttpServletRequest req) {
        AdminAccess.application(req, webhooks.webhook(id).applicationId());
        webhooks.delete(id, AdminAccess.of(req).actor());
        return OkVO.OK;
    }

    @RequiresFunction(DOMAIN_VIEW)
    @GetMapping("/webhooks/{id}/deliveries")
    public PageVO<WebhookDeliveryVO> deliveries(@PathVariable long id,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int size,
                                                HttpServletRequest req) {
        AdminAccess.application(req, webhooks.webhook(id).applicationId());
        return WebhookVoMapper.toDeliveryPageVO(webhooks.deliveries(id,
                Math.max(1, page), Math.min(200, Math.max(1, size))));
    }

    @RequiresFunction(APPLICATION_CREDENTIALS)
    @PostMapping("/webhooks/{id}/test")
    public OkVO test(@PathVariable long id, HttpServletRequest req) {
        AdminAccess.application(req, webhooks.webhook(id).applicationId());
        webhooks.test(id, AdminAccess.of(req).actor());
        return OkVO.OK;
    }
}
