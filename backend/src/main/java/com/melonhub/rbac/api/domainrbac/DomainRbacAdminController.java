package com.melonhub.rbac.api.domainrbac;

import static com.melonhub.rbac.service.port.security.AdminFunction.DOMAIN_ASSIGN;
import static com.melonhub.rbac.service.port.security.AdminFunction.DOMAIN_CONFIGURE;
import static com.melonhub.rbac.service.port.security.AdminFunction.DOMAIN_TRANSFER;
import static com.melonhub.rbac.service.port.security.AdminFunction.DOMAIN_VIEW;

import com.melonhub.rbac.api.domainrbac.dto.BatchUserRolesDTO;
import com.melonhub.rbac.api.domainrbac.dto.DomainCopyDTO;
import com.melonhub.rbac.api.domainrbac.dto.PermissionDTO;
import com.melonhub.rbac.api.domainrbac.dto.RoleDTO;
import com.melonhub.rbac.api.domainrbac.dto.SimulationDTO;
import com.melonhub.rbac.api.domainrbac.dto.UserRolesDTO;
import com.melonhub.rbac.api.domainrbac.vo.BatchUserRolesResultVO;
import com.melonhub.rbac.api.domainrbac.vo.DomainAssignmentVO;
import com.melonhub.rbac.api.domainrbac.vo.DomainCopyResultVO;
import com.melonhub.rbac.api.domainrbac.vo.DomainOverviewVO;
import com.melonhub.rbac.api.domainrbac.vo.PermissionVO;
import com.melonhub.rbac.api.domainrbac.vo.RoleMemberVO;
import com.melonhub.rbac.api.domainrbac.vo.RoleVO;
import com.melonhub.rbac.api.domainrbac.vo.SimulationVO;
import com.melonhub.rbac.api.domainrbac.vo.UserRoleAssignmentsVO;
import com.melonhub.rbac.api.shared.dto.EnabledDTO;
import com.melonhub.rbac.api.shared.dto.IdsDTO;
import com.melonhub.rbac.api.shared.vo.IdVO;
import com.melonhub.rbac.api.shared.vo.OkVO;
import com.melonhub.rbac.api.shared.vo.PageVO;
import com.melonhub.rbac.infrastructure.security.AdminAccess;
import com.melonhub.rbac.infrastructure.security.RequiresFunction;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.BatchUserRolesCommand;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.DomainCopyCommand;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.SavePermissionCommand;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.SaveRoleCommand;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.SimulationCommand;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台隔离域（部门 × 软件）RBAC1 角色、权限、继承、授权分配、复制与诊断接口。
 */
@RestController
@RequestMapping("/api/admin")
public class DomainRbacAdminController {
    private final DomainRbacUseCase domainRbac;

    public DomainRbacAdminController(DomainRbacUseCase domainRbac) {
        this.domainRbac = domainRbac;
    }

    @RequiresFunction(DOMAIN_VIEW)
    @GetMapping("/roles")
    public List<RoleVO> roles(@RequestParam long departmentId, @RequestParam long applicationId, HttpServletRequest req) {
        AdminAccess.domain(req, departmentId, applicationId);
        return domainRbac.roles(departmentId, applicationId).stream().map(DomainRbacVoMapper::toRoleVO).toList();
    }

    @RequiresFunction(DOMAIN_CONFIGURE)
    @PostMapping("/roles")
    public IdVO createRole(@RequestParam long departmentId, @RequestParam long applicationId,
                           @Valid @RequestBody RoleDTO body, HttpServletRequest req) {
        return new IdVO(domainRbac.role(new SaveRoleCommand(body.name(), body.code(), body.description()),
                null, departmentId, applicationId, AdminAccess.domain(req, departmentId, applicationId).actor()));
    }

    @RequiresFunction(DOMAIN_CONFIGURE)
    @PutMapping("/roles/{id}")
    public IdVO updateRole(@PathVariable long id, @RequestParam long departmentId, @RequestParam long applicationId,
                           @Valid @RequestBody RoleDTO body, HttpServletRequest req) {
        return new IdVO(domainRbac.role(new SaveRoleCommand(body.name(), body.code(), body.description()),
                id, departmentId, applicationId, AdminAccess.domain(req, departmentId, applicationId).actor()));
    }

    @RequiresFunction(DOMAIN_CONFIGURE)
    @PatchMapping("/roles/{id}/status")
    public OkVO roleStatus(@PathVariable long id, @RequestParam long departmentId, @RequestParam long applicationId,
                           @Valid @RequestBody EnabledDTO body, HttpServletRequest req) {
        domainRbac.roleStatus(id, departmentId, applicationId, body.enabled(),
                AdminAccess.domain(req, departmentId, applicationId).actor());
        return OkVO.OK;
    }

    @RequiresFunction(DOMAIN_CONFIGURE)
    @DeleteMapping("/roles/{id}")
    public OkVO deleteRole(@PathVariable long id, @RequestParam long departmentId, @RequestParam long applicationId,
                           HttpServletRequest req) {
        domainRbac.deleteRole(id, departmentId, applicationId, AdminAccess.domain(req, departmentId, applicationId).actor());
        return OkVO.OK;
    }

    @RequiresFunction(DOMAIN_CONFIGURE)
    @PutMapping("/roles/{id}/permissions")
    public OkVO rolePermissions(@PathVariable long id, @RequestParam long departmentId, @RequestParam long applicationId,
                                @Valid @RequestBody IdsDTO body, HttpServletRequest req) {
        domainRbac.rolePermissions(id, departmentId, applicationId, body.ids(),
                AdminAccess.domain(req, departmentId, applicationId).actor());
        return OkVO.OK;
    }

    @RequiresFunction(DOMAIN_CONFIGURE)
    @PutMapping("/roles/{id}/parents")
    public OkVO roleParents(@PathVariable long id, @RequestParam long departmentId, @RequestParam long applicationId,
                            @Valid @RequestBody IdsDTO body, HttpServletRequest req) {
        domainRbac.roleParents(id, departmentId, applicationId, body.ids(),
                AdminAccess.domain(req, departmentId, applicationId).actor());
        return OkVO.OK;
    }

    @RequiresFunction(DOMAIN_VIEW)
    @GetMapping("/roles/{id}/members")
    public List<RoleMemberVO> roleMembers(@PathVariable long id, @RequestParam long departmentId,
                                          @RequestParam long applicationId, HttpServletRequest req) {
        AdminAccess.domain(req, departmentId, applicationId);
        return domainRbac.roleMembers(id, departmentId, applicationId).stream()
                .map(DomainRbacVoMapper::toRoleMemberVO).toList();
    }

    @RequiresFunction(DOMAIN_ASSIGN)
    @PutMapping("/roles/{id}/members")
    public OkVO replaceRoleMembers(@PathVariable long id, @RequestParam long departmentId,
                                   @RequestParam long applicationId, @Valid @RequestBody IdsDTO body,
                                   HttpServletRequest req) {
        domainRbac.roleMembers(id, departmentId, applicationId, body.ids(),
                AdminAccess.domain(req, departmentId, applicationId).actor());
        return OkVO.OK;
    }

    @RequiresFunction(DOMAIN_VIEW)
    @GetMapping("/permissions")
    public List<PermissionVO> permissions(@RequestParam long departmentId, @RequestParam long applicationId,
                                          HttpServletRequest req) {
        AdminAccess.domain(req, departmentId, applicationId);
        return domainRbac.permissions(departmentId, applicationId).stream()
                .map(DomainRbacVoMapper::toPermissionVO).toList();
    }

    @RequiresFunction(DOMAIN_CONFIGURE)
    @PostMapping("/permissions")
    public IdVO createPermission(@RequestParam long departmentId, @RequestParam long applicationId,
                                 @Valid @RequestBody PermissionDTO body, HttpServletRequest req) {
        return new IdVO(domainRbac.permission(new SavePermissionCommand(body.name(), body.code(), body.resource(), body.action()),
                null, departmentId, applicationId, AdminAccess.domain(req, departmentId, applicationId).actor()));
    }

    @RequiresFunction(DOMAIN_CONFIGURE)
    @PutMapping("/permissions/{id}")
    public IdVO updatePermission(@PathVariable long id, @RequestParam long departmentId, @RequestParam long applicationId,
                                 @Valid @RequestBody PermissionDTO body, HttpServletRequest req) {
        return new IdVO(domainRbac.permission(new SavePermissionCommand(body.name(), body.code(), body.resource(), body.action()),
                id, departmentId, applicationId, AdminAccess.domain(req, departmentId, applicationId).actor()));
    }

    @RequiresFunction(DOMAIN_CONFIGURE)
    @DeleteMapping("/permissions/{id}")
    public OkVO deletePermission(@PathVariable long id, @RequestParam long departmentId, @RequestParam long applicationId,
                                 HttpServletRequest req) {
        domainRbac.deletePermission(id, departmentId, applicationId,
                AdminAccess.domain(req, departmentId, applicationId).actor());
        return OkVO.OK;
    }

    @RequiresFunction(DOMAIN_VIEW)
    @GetMapping("/users/{id}/roles")
    public UserRoleAssignmentsVO userRoles(@PathVariable long id, @RequestParam long departmentId,
                                           @RequestParam long applicationId, HttpServletRequest req) {
        AdminAccess.domain(req, departmentId, applicationId);
        return DomainRbacVoMapper.toUserRoleAssignmentsVO(domainRbac.assignments(id, departmentId, applicationId));
    }

    @RequiresFunction(DOMAIN_ASSIGN)
    @PutMapping("/users/{id}/roles")
    public OkVO updateUserRoles(@PathVariable long id, @RequestParam long departmentId, @RequestParam long applicationId,
                                @Valid @RequestBody UserRolesDTO body, HttpServletRequest req) {
        domainRbac.userRoles(id, departmentId, applicationId, body.ids(), body.expiresInDays(),
                DomainRbacVoMapper.toRoleExpiryCommands(body.expiries()),
                AdminAccess.domain(req, departmentId, applicationId).actor());
        return OkVO.OK;
    }

    @RequiresFunction(DOMAIN_VIEW)
    @GetMapping("/assignments")
    public PageVO<DomainAssignmentVO> assignments(@RequestParam long departmentId, @RequestParam long applicationId,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int size,
                                                  HttpServletRequest req) {
        AdminAccess.domain(req, departmentId, applicationId);
        return DomainRbacVoMapper.toDomainAssignmentPageVO(domainRbac.domainAssignments(
                departmentId, applicationId, keyword, Math.max(1, page), Math.min(200, Math.max(1, size))));
    }

    @RequiresFunction(DOMAIN_ASSIGN)
    @PostMapping("/assignments/batch")
    public BatchUserRolesResultVO addUserRoles(@RequestParam long departmentId, @RequestParam long applicationId,
                                               @Valid @RequestBody BatchUserRolesDTO body, HttpServletRequest req) {
        return DomainRbacVoMapper.toBatchUserRolesResultVO(domainRbac.addUserRoles(departmentId, applicationId,
                new BatchUserRolesCommand(body.userIds(), body.roleIds(), body.expiresInDays()),
                AdminAccess.domain(req, departmentId, applicationId).actor()));
    }

    @RequiresFunction(DOMAIN_TRANSFER)
    @PostMapping("/domains/copy")
    public DomainCopyResultVO copy(@Valid @RequestBody DomainCopyDTO body, HttpServletRequest req) {
        AdminAccess.domain(req, body.sourceDepartmentId(), body.applicationId());
        return DomainRbacVoMapper.toDomainCopyResultVO(domainRbac.copyDomain(
                new DomainCopyCommand(body.applicationId(), body.sourceDepartmentId(), body.targetDepartmentId()),
                AdminAccess.domain(req, body.targetDepartmentId(), body.applicationId()).actor()));
    }

    @RequiresFunction(DOMAIN_VIEW)
    @PostMapping("/simulate")
    public SimulationVO simulate(@Valid @RequestBody SimulationDTO body, HttpServletRequest req) {
        AdminAccess.domain(req, body.departmentId(), body.applicationId());
        return DomainRbacVoMapper.toSimulationVO(domainRbac.simulate(new SimulationCommand(
                body.userId(), body.departmentId(), body.applicationId(), body.activeRoleIds(), body.permissions())));
    }

    @RequiresFunction(DOMAIN_VIEW)
    @GetMapping("/overview")
    public DomainOverviewVO overview(@RequestParam long departmentId, @RequestParam long applicationId,
                                     HttpServletRequest req) {
        AdminAccess.domain(req, departmentId, applicationId);
        return DomainRbacVoMapper.toDomainOverviewVO(domainRbac.overview(departmentId, applicationId));
    }
}
