package com.melonhub.rbac.api.domainrbac;

import com.melonhub.rbac.api.domainrbac.dto.PermissionDTO;
import com.melonhub.rbac.api.domainrbac.dto.RoleDTO;
import com.melonhub.rbac.api.domainrbac.dto.UserRolesDTO;
import com.melonhub.rbac.api.domainrbac.vo.PermissionVO;
import com.melonhub.rbac.api.domainrbac.vo.RoleVO;
import com.melonhub.rbac.api.domainrbac.vo.UserRoleAssignmentsVO;
import com.melonhub.rbac.api.shared.dto.EnabledDTO;
import com.melonhub.rbac.api.shared.dto.IdsDTO;
import com.melonhub.rbac.api.shared.vo.IdVO;
import com.melonhub.rbac.api.shared.vo.OkVO;
import com.melonhub.rbac.infrastructure.security.ApplicationAccess;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.SavePermissionCommand;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.SaveRoleCommand;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacUseCase;
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
 * 业务系统隔离域 RBAC1 角色、权限、继承与用户授权接口（{@code /api/v1/departments/{dept}/**}）。
 */
@RestController
@RequestMapping("/api/v1")
public class DomainRbacBusinessController {
    private final DomainRbacUseCase domainRbac;

    public DomainRbacBusinessController(DomainRbacUseCase domainRbac) {
        this.domainRbac = domainRbac;
    }

    private static long app(HttpServletRequest req) {
        return ApplicationAccess.of(req).applicationId();
    }

    private static String actor(HttpServletRequest req) {
        return ApplicationAccess.of(req).actor();
    }

    @GetMapping("/departments/{dept}/roles")
    public List<RoleVO> roles(@PathVariable long dept, HttpServletRequest req) {
        return domainRbac.roles(dept, app(req)).stream().map(DomainRbacVoMapper::toRoleVO).toList();
    }

    @PostMapping("/departments/{dept}/roles")
    public IdVO createRole(@PathVariable long dept, @Valid @RequestBody RoleDTO body, HttpServletRequest req) {
        return new IdVO(domainRbac.role(new SaveRoleCommand(body.name(), body.code(), body.description()),
                null, dept, app(req), actor(req)));
    }

    @PutMapping("/departments/{dept}/roles/{id}")
    public IdVO updateRole(@PathVariable long dept, @PathVariable long id, @Valid @RequestBody RoleDTO body,
                           HttpServletRequest req) {
        return new IdVO(domainRbac.role(new SaveRoleCommand(body.name(), body.code(), body.description()),
                id, dept, app(req), actor(req)));
    }

    @PatchMapping("/departments/{dept}/roles/{id}/status")
    public OkVO roleStatus(@PathVariable long dept, @PathVariable long id, @Valid @RequestBody EnabledDTO body,
                           HttpServletRequest req) {
        domainRbac.roleStatus(id, dept, app(req), body.enabled(), actor(req));
        return OkVO.OK;
    }

    @DeleteMapping("/departments/{dept}/roles/{id}")
    public OkVO deleteRole(@PathVariable long dept, @PathVariable long id, HttpServletRequest req) {
        domainRbac.deleteRole(id, dept, app(req), actor(req));
        return OkVO.OK;
    }

    @GetMapping("/departments/{dept}/permissions")
    public List<PermissionVO> permissions(@PathVariable long dept, HttpServletRequest req) {
        return domainRbac.permissions(dept, app(req)).stream().map(DomainRbacVoMapper::toPermissionVO).toList();
    }

    @PostMapping("/departments/{dept}/permissions")
    public IdVO createPermission(@PathVariable long dept, @Valid @RequestBody PermissionDTO body, HttpServletRequest req) {
        return new IdVO(domainRbac.permission(new SavePermissionCommand(body.name(), body.code(), body.resource(), body.action()),
                null, dept, app(req), actor(req)));
    }

    @PutMapping("/departments/{dept}/permissions/{id}")
    public IdVO updatePermission(@PathVariable long dept, @PathVariable long id, @Valid @RequestBody PermissionDTO body,
                                 HttpServletRequest req) {
        return new IdVO(domainRbac.permission(new SavePermissionCommand(body.name(), body.code(), body.resource(), body.action()),
                id, dept, app(req), actor(req)));
    }

    @DeleteMapping("/departments/{dept}/permissions/{id}")
    public OkVO deletePermission(@PathVariable long dept, @PathVariable long id, HttpServletRequest req) {
        domainRbac.deletePermission(id, dept, app(req), actor(req));
        return OkVO.OK;
    }

    @PutMapping("/departments/{dept}/roles/{id}/permissions")
    public OkVO rolePermissions(@PathVariable long dept, @PathVariable long id, @Valid @RequestBody IdsDTO body,
                                HttpServletRequest req) {
        domainRbac.rolePermissions(id, dept, app(req), body.ids(), actor(req));
        return OkVO.OK;
    }

    @PutMapping("/departments/{dept}/roles/{id}/parents")
    public OkVO roleParents(@PathVariable long dept, @PathVariable long id, @Valid @RequestBody IdsDTO body,
                            HttpServletRequest req) {
        domainRbac.roleParents(id, dept, app(req), body.ids(), actor(req));
        return OkVO.OK;
    }

    @GetMapping("/departments/{dept}/users/{id}/roles")
    public UserRoleAssignmentsVO userRoles(@PathVariable long dept, @PathVariable long id, HttpServletRequest req) {
        return DomainRbacVoMapper.toUserRoleAssignmentsVO(domainRbac.assignments(id, dept, app(req)));
    }

    @PutMapping("/departments/{dept}/users/{id}/roles")
    public OkVO updateUserRoles(@PathVariable long dept, @PathVariable long id, @Valid @RequestBody UserRolesDTO body,
                                HttpServletRequest req) {
        domainRbac.userRoles(id, dept, app(req), body.ids(), body.expiresInDays(),
                DomainRbacVoMapper.toRoleExpiryCommands(body.expiries()), actor(req));
        return OkVO.OK;
    }
}
