package com.melonhub.rbac.api.organization;

import static com.melonhub.rbac.service.port.security.AdminFunction.ORG_VIEW;
import static com.melonhub.rbac.service.port.security.AdminFunction.USER_CREDENTIALS;
import static com.melonhub.rbac.service.port.security.AdminFunction.USER_DELETE;
import static com.melonhub.rbac.service.port.security.AdminFunction.USER_MANAGE;
import static com.melonhub.rbac.service.port.security.AdminFunction.USER_STATUS;

import com.melonhub.rbac.api.organization.dto.IdentityPasswordDTO;
import com.melonhub.rbac.api.organization.dto.UserCreateDTO;
import com.melonhub.rbac.api.organization.dto.UserDTO;
import com.melonhub.rbac.api.organization.dto.UserImportDTO;
import com.melonhub.rbac.api.organization.vo.UserAccessVO;
import com.melonhub.rbac.api.organization.vo.UserImportResultVO;
import com.melonhub.rbac.api.organization.vo.UserVO;
import com.melonhub.rbac.api.shared.dto.EnabledDTO;
import com.melonhub.rbac.api.shared.vo.IdVO;
import com.melonhub.rbac.api.shared.vo.OkVO;
import com.melonhub.rbac.api.shared.vo.PageVO;
import com.melonhub.rbac.api.shared.vo.RevokedCountVO;
import com.melonhub.rbac.infrastructure.security.AdminAccess;
import com.melonhub.rbac.infrastructure.security.RequiresFunction;
import com.melonhub.rbac.service.port.governance.GovernanceModels.UserImportCommand;
import com.melonhub.rbac.service.port.governance.GovernanceModels.UserImportRowCommand;
import com.melonhub.rbac.service.port.governance.GovernanceUseCase;
import com.melonhub.rbac.service.port.identity.IdentityModels.CreateUserWithPasswordCommand;
import com.melonhub.rbac.service.port.identity.IdentityUseCase;
import com.melonhub.rbac.service.port.organization.OrganizationModels.SaveUserCommand;
import com.melonhub.rbac.service.port.organization.OrganizationUseCase;
import com.melonhub.rbac.service.port.security.AdminPrincipal;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台用户生命周期、凭据密码设置、批量导入与强制下线接口。
 */
@RestController
@RequestMapping("/api/admin/users")
public class UserController {
    private final OrganizationUseCase organization;
    private final IdentityUseCase identity;
    private final GovernanceUseCase governance;

    public UserController(OrganizationUseCase organization, IdentityUseCase identity, GovernanceUseCase governance) {
        this.organization = organization;
        this.identity = identity;
        this.governance = governance;
    }

    @RequiresFunction(ORG_VIEW)
    @GetMapping
    public PageVO<UserVO> list(@RequestParam(required = false) String keyword,
                               @RequestParam(required = false) Long departmentId,
                               @RequestParam(required = false) Boolean enabled,
                               @RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "20") int size,
                               HttpServletRequest req) {
        AdminPrincipal principal = departmentId == null ? AdminAccess.of(req) : AdminAccess.department(req, departmentId);
        return OrganizationVoMapper.toUserPageVO(organization.users(keyword, departmentId, enabled,
                principal.visibleDepartments(), Math.max(1, page), Math.min(200, Math.max(1, size))));
    }

    @RequiresFunction(ORG_VIEW)
    @GetMapping("/export")
    public PageVO<UserVO> export(@RequestParam(required = false) String keyword,
                                 @RequestParam(required = false) Long departmentId,
                                 @RequestParam(required = false) Boolean enabled,
                                 HttpServletRequest req) {
        AdminPrincipal principal = departmentId == null ? AdminAccess.of(req) : AdminAccess.department(req, departmentId);
        return OrganizationVoMapper.toUserPageVO(organization.users(keyword, departmentId, enabled,
                principal.visibleDepartments(), 1, Integer.MAX_VALUE));
    }

    @RequiresFunction(USER_MANAGE)
    @PostMapping
    public IdVO create(@Valid @RequestBody UserCreateDTO body, HttpServletRequest req) {
        AdminPrincipal principal = AdminAccess.of(req);
        return new IdVO(identity.createUser(new CreateUserWithPasswordCommand(
                body.name(), body.externalId(), body.email(), body.departmentIds(), body.identityPassword()), principal));
    }

    @RequiresFunction(USER_MANAGE)
    @PostMapping("/import")
    public UserImportResultVO importUsers(@Valid @RequestBody UserImportDTO body, HttpServletRequest req) {
        List<UserImportRowCommand> rows = body.users().stream()
                .map(r -> r == null ? null : new UserImportRowCommand(r.name(), r.externalId(), r.email(), r.departmentCodes()))
                .toList();
        return OrganizationVoMapper.toUserImportResultVO(
                governance.importUsers(new UserImportCommand(rows), AdminAccess.allDepartments(req).actor()));
    }

    @RequiresFunction(USER_CREDENTIALS)
    @PutMapping("/{id}/identity-password")
    public OkVO setIdentityPassword(@PathVariable long id, @Valid @RequestBody IdentityPasswordDTO body,
                                    HttpServletRequest req) {
        identity.setUserPassword(id, body.password(), AdminAccess.of(req));
        return OkVO.OK;
    }

    @RequiresFunction(USER_MANAGE)
    @PutMapping("/{id}")
    public IdVO update(@PathVariable long id, @Valid @RequestBody UserDTO body, HttpServletRequest req) {
        AdminPrincipal principal = AdminAccess.of(req);
        return new IdVO(organization.user(new SaveUserCommand(body.name(), body.externalId(), body.email(), body.departmentIds()),
                id, principal.actor(), principal.visibleDepartments()));
    }

    @RequiresFunction(USER_STATUS)
    @PatchMapping("/{id}/status")
    public OkVO status(@PathVariable long id, @Valid @RequestBody EnabledDTO body, HttpServletRequest req) {
        AdminPrincipal principal = AdminAccess.of(req);
        organization.userStatus(id, body.enabled(), principal.actor(), principal.visibleDepartments());
        return OkVO.OK;
    }

    @RequiresFunction(USER_DELETE)
    @DeleteMapping("/{id}")
    public OkVO delete(@PathVariable long id, HttpServletRequest req) {
        AdminPrincipal principal = AdminAccess.of(req);
        organization.deleteUser(id, principal.actor(), principal.visibleDepartments());
        return OkVO.OK;
    }

    @RequiresFunction(ORG_VIEW)
    @GetMapping("/{id}/access")
    public UserAccessVO access(@PathVariable long id, HttpServletRequest req) {
        return OrganizationVoMapper.toUserAccessVO(organization.userAccess(id, AdminAccess.of(req).visibility()));
    }

    @RequiresFunction(USER_STATUS)
    @DeleteMapping("/{id}/contexts")
    public RevokedCountVO revokeContexts(@PathVariable long id, HttpServletRequest req) {
        AdminPrincipal principal = AdminAccess.of(req);
        return new RevokedCountVO(organization.revokeUserContexts(id, principal.actor(), principal.visibleDepartments()));
    }
}
