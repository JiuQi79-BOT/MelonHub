package com.melonhub.rbac.api.application;

import static com.melonhub.rbac.service.port.security.AdminFunction.APPLICATION_CREDENTIALS;
import static com.melonhub.rbac.service.port.security.AdminFunction.APPLICATION_MANAGE;
import static com.melonhub.rbac.service.port.security.AdminFunction.DOMAIN_VIEW;
import static com.melonhub.rbac.service.port.security.AdminFunction.ORG_VIEW;

import com.melonhub.rbac.api.application.dto.ApiKeyDTO;
import com.melonhub.rbac.api.application.dto.ApplicationDTO;
import com.melonhub.rbac.api.application.dto.RateLimitUpdateDTO;
import com.melonhub.rbac.api.application.vo.ApiKeyCreatedVO;
import com.melonhub.rbac.api.application.vo.ApiKeyRotatedVO;
import com.melonhub.rbac.api.application.vo.ApiKeyVO;
import com.melonhub.rbac.api.application.vo.ApplicationCreatedVO;
import com.melonhub.rbac.api.application.vo.ApplicationVO;
import com.melonhub.rbac.api.shared.dto.EnabledDTO;
import com.melonhub.rbac.api.shared.vo.IdVO;
import com.melonhub.rbac.api.shared.vo.OkVO;
import com.melonhub.rbac.infrastructure.security.AdminAccess;
import com.melonhub.rbac.infrastructure.security.RequiresFunction;
import com.melonhub.rbac.service.port.application.ApplicationModels.CreateApiKeyCommand;
import com.melonhub.rbac.service.port.application.ApplicationModels.SaveApplicationCommand;
import com.melonhub.rbac.service.port.application.ApplicationUseCase;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台业务软件、API Key 与限额管理接口。
 */
@RestController
@RequestMapping("/api/admin/applications")
public class ApplicationController {
    private final ApplicationUseCase applications;

    public ApplicationController(ApplicationUseCase applications) {
        this.applications = applications;
    }

    @RequiresFunction(ORG_VIEW)
    @GetMapping
    public List<ApplicationVO> list(HttpServletRequest req) {
        return applications.applications(AdminAccess.of(req).visibleApplications()).stream()
                .map(ApplicationVoMapper::toApplicationVO).toList();
    }

    @RequiresFunction(APPLICATION_MANAGE)
    @PostMapping
    public ApplicationCreatedVO create(@Valid @RequestBody ApplicationDTO body, HttpServletRequest req) {
        return ApplicationVoMapper.toApplicationCreatedVO(applications.application(
                new SaveApplicationCommand(body.name(), body.code(), body.description(), body.owner()),
                AdminAccess.of(req).actor()));
    }

    @RequiresFunction(APPLICATION_MANAGE)
    @PutMapping("/{id}")
    public IdVO update(@PathVariable long id, @Valid @RequestBody ApplicationDTO body, HttpServletRequest req) {
        return new IdVO(applications.updateApplication(id,
                new SaveApplicationCommand(body.name(), body.code(), body.description(), body.owner()),
                AdminAccess.of(req).actor()));
    }

    @RequiresFunction(APPLICATION_MANAGE)
    @PatchMapping("/{id}/status")
    public OkVO status(@PathVariable long id, @Valid @RequestBody EnabledDTO body, HttpServletRequest req) {
        applications.applicationStatus(id, body.enabled(), AdminAccess.of(req).actor());
        return OkVO.OK;
    }

    @RequiresFunction(APPLICATION_MANAGE)
    @DeleteMapping("/{id}")
    public OkVO delete(@PathVariable long id, HttpServletRequest req) {
        applications.deleteApplication(id, AdminAccess.of(req).actor());
        return OkVO.OK;
    }

    @RequiresFunction(APPLICATION_MANAGE)
    @PutMapping("/{id}/rate-limit")
    public OkVO rateLimit(@PathVariable long id, @Valid @RequestBody RateLimitUpdateDTO body, HttpServletRequest req) {
        applications.applicationRateLimit(id, body.perSecond(), AdminAccess.of(req).actor());
        return OkVO.OK;
    }

    @RequiresFunction(DOMAIN_VIEW)
    @GetMapping("/{id}/keys")
    public List<ApiKeyVO> keys(@PathVariable long id, HttpServletRequest req) {
        AdminAccess.application(req, id);
        return applications.keys(id).stream().map(ApplicationVoMapper::toApiKeyVO).toList();
    }

    @RequiresFunction(APPLICATION_CREDENTIALS)
    @PostMapping("/{id}/keys")
    public ApiKeyCreatedVO createKey(@PathVariable long id, @Valid @RequestBody ApiKeyDTO body, HttpServletRequest req) {
        return ApplicationVoMapper.toApiKeyCreatedVO(applications.createKey(id,
                new CreateApiKeyCommand(body.name(), body.expiresInDays()), AdminAccess.application(req, id).actor()));
    }

    @RequiresFunction(APPLICATION_CREDENTIALS)
    @DeleteMapping("/{id}/keys/{keyId}")
    public OkVO revokeKey(@PathVariable long id, @PathVariable long keyId, HttpServletRequest req) {
        applications.revokeKey(id, keyId, AdminAccess.application(req, id).actor());
        return OkVO.OK;
    }

    @RequiresFunction(APPLICATION_CREDENTIALS)
    @PostMapping("/{id}/rotate-key")
    public ApiKeyRotatedVO rotateKey(@PathVariable long id, HttpServletRequest req) {
        return new ApiKeyRotatedVO(applications.rotateKey(id, AdminAccess.application(req, id).actor()));
    }
}
