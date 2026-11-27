package com.melonhub.rbac.api.context;

import com.melonhub.rbac.api.context.dto.ContextIssueDTO;
import com.melonhub.rbac.api.context.dto.PermissionCheckDTO;
import com.melonhub.rbac.api.context.vo.CheckVO;
import com.melonhub.rbac.api.context.vo.ContextVO;
import com.melonhub.rbac.api.shared.vo.OkVO;
import com.melonhub.rbac.infrastructure.security.ApplicationAccess;
import com.melonhub.rbac.service.port.context.ContextUseCase;
import com.melonhub.rbac.service.port.security.ApplicationCaller;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 业务系统在线权限上下文签发、生效查询、批量鉴权与撤销接口（{@code /api/v1/contexts/**}）。
 */
@RestController
@RequestMapping("/api/v1/contexts")
public class ContextBusinessController {
    private final ContextUseCase contexts;

    public ContextBusinessController(ContextUseCase contexts) {
        this.contexts = contexts;
    }

    @PostMapping
    public ContextVO issue(@Valid @RequestBody ContextIssueDTO body, HttpServletRequest req) {
        ApplicationCaller caller = ApplicationAccess.of(req);
        return ContextVoMapper.toContextVO(contexts.issueContext(
                caller.applicationId(), ContextVoMapper.toCommand(body), caller.actor()));
    }

    @GetMapping("/effective")
    public ContextVO effective(@RequestHeader("X-Context-Token") String token, HttpServletRequest req) {
        ApplicationCaller caller = ApplicationAccess.of(req);
        return ContextVoMapper.toContextVO(contexts.effective(caller.applicationId(), token));
    }

    @PostMapping("/check")
    public CheckVO check(@RequestHeader("X-Context-Token") String token,
                         @Valid @RequestBody PermissionCheckDTO body,
                         HttpServletRequest req) {
        ApplicationCaller caller = ApplicationAccess.of(req);
        return ContextVoMapper.toCheckVO(contexts.check(caller.applicationId(), token, body.permissions()));
    }

    @DeleteMapping
    public OkVO revoke(@RequestHeader("X-Context-Token") String token, HttpServletRequest req) {
        ApplicationCaller caller = ApplicationAccess.of(req);
        contexts.revoke(caller.applicationId(), token, caller.actor());
        return OkVO.OK;
    }
}
