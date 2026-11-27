package com.melonhub.rbac.api.governance;

import com.melonhub.rbac.api.governance.dto.AccessRequestCancelDTO;
import com.melonhub.rbac.api.governance.dto.AccessRequestSubmitDTO;
import com.melonhub.rbac.api.governance.vo.AccessRequestVO;
import com.melonhub.rbac.api.governance.vo.SelfAccessVO;
import com.melonhub.rbac.api.shared.vo.OkVO;
import com.melonhub.rbac.infrastructure.security.ApplicationAccess;
import com.melonhub.rbac.service.port.governance.GovernanceModels.SubmitAccessRequestCommand;
import com.melonhub.rbac.service.port.governance.GovernanceUseCase;
import com.melonhub.rbac.service.port.security.ApplicationCaller;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 业务系统员工自助角色申请、撤回与权限自查接口（{@code /api/v1/access-requests/**}、{@code /api/v1/users/access}）。
 */
@RestController
@RequestMapping("/api/v1")
public class GovernanceBusinessController {
    private final GovernanceUseCase governance;

    public GovernanceBusinessController(GovernanceUseCase governance) {
        this.governance = governance;
    }

    @PostMapping("/access-requests")
    public AccessRequestVO submitRequest(@Valid @RequestBody AccessRequestSubmitDTO body, HttpServletRequest req) {
        ApplicationCaller caller = ApplicationAccess.of(req);
        return GovernanceVoMapper.toAccessRequestVO(governance.submit(caller.applicationId(),
                new SubmitAccessRequestCommand(body.externalId(), body.departmentId(), body.roleId(),
                        body.reason(), body.durationDays()),
                caller.actor()));
    }

    @GetMapping("/access-requests")
    public List<AccessRequestVO> myRequests(@RequestParam String externalId, HttpServletRequest req) {
        ApplicationCaller caller = ApplicationAccess.of(req);
        return governance.myRequests(caller.applicationId(), externalId).stream()
                .map(GovernanceVoMapper::toAccessRequestVO).toList();
    }

    @PostMapping("/access-requests/{id}/cancel")
    public OkVO cancelRequest(@PathVariable long id, @Valid @RequestBody AccessRequestCancelDTO body,
                              HttpServletRequest req) {
        ApplicationCaller caller = ApplicationAccess.of(req);
        governance.cancelOwn(caller.applicationId(), id, body.externalId(), caller.actor());
        return OkVO.OK;
    }

    @GetMapping("/users/access")
    public SelfAccessVO myAccess(@RequestParam String externalId, @RequestParam long departmentId,
                                 HttpServletRequest req) {
        ApplicationCaller caller = ApplicationAccess.of(req);
        return GovernanceVoMapper.toSelfAccessVO(governance.myAccess(caller.applicationId(), externalId, departmentId));
    }
}
