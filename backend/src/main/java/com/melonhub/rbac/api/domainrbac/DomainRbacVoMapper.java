package com.melonhub.rbac.api.domainrbac;

import com.melonhub.rbac.api.domainrbac.dto.RoleExpiryDTO;
import com.melonhub.rbac.api.domainrbac.vo.BatchUserRolesResultVO;
import com.melonhub.rbac.api.domainrbac.vo.DomainAssignmentVO;
import com.melonhub.rbac.api.domainrbac.vo.DomainCopyResultVO;
import com.melonhub.rbac.api.domainrbac.vo.DomainOverviewVO;
import com.melonhub.rbac.api.domainrbac.vo.PermissionVO;
import com.melonhub.rbac.api.domainrbac.vo.RoleMemberVO;
import com.melonhub.rbac.api.domainrbac.vo.RoleVO;
import com.melonhub.rbac.api.domainrbac.vo.SimulatedPermissionVO;
import com.melonhub.rbac.api.domainrbac.vo.SimulatedRoleVO;
import com.melonhub.rbac.api.domainrbac.vo.SimulationVO;
import com.melonhub.rbac.api.domainrbac.vo.UserRoleAssignmentsVO;
import com.melonhub.rbac.api.organization.OrganizationVoMapper;
import com.melonhub.rbac.api.shared.vo.PageVO;
import com.melonhub.rbac.service.port.common.PageResult;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.BatchUserRolesResult;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.DomainAssignmentResult;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.DomainCopyResult;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.DomainOverviewResult;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.PermissionResult;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.RoleExpiryCommand;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.RoleMemberResult;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.RoleResult;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.SimulationResult;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.UserRoleAssignmentsResult;
import java.util.List;

/**
 * 隔离域 RBAC1 模块命令与 VO 映射器。
 */
public final class DomainRbacVoMapper {
    private DomainRbacVoMapper() {}

    public static List<RoleExpiryCommand> toRoleExpiryCommands(List<RoleExpiryDTO> expiries) {
        if (expiries == null) {
            return null;
        }
        return expiries.stream().map(e -> new RoleExpiryCommand(e.roleId(), e.expiresAt())).toList();
    }

    public static RoleVO toRoleVO(RoleResult r) {
        return new RoleVO(r.id(), r.name(), r.code(), r.description(), r.enabled(), r.createdAt(),
                r.memberCount(), r.permissionIds(), r.parentRoleIds());
    }

    public static PermissionVO toPermissionVO(PermissionResult r) {
        return new PermissionVO(r.id(), r.name(), r.code(), r.resource(), r.action(), r.createdAt(), r.roleCount());
    }

    public static RoleMemberVO toRoleMemberVO(RoleMemberResult r) {
        return new RoleMemberVO(r.id(), r.name(), r.externalId(), r.email(), r.enabled());
    }

    public static UserRoleAssignmentsVO toUserRoleAssignmentsVO(UserRoleAssignmentsResult r) {
        return new UserRoleAssignmentsVO(r.roleIds(), r.roleExpiries());
    }

    public static PageVO<DomainAssignmentVO> toDomainAssignmentPageVO(PageResult<DomainAssignmentResult> p) {
        return new PageVO<>(p.items().stream().map(i -> new DomainAssignmentVO(
                i.id(), i.name(), i.externalId(), i.email(), i.enabled(), i.roleIds(), i.roleExpiries())).toList(),
                p.total(), p.page(), p.size());
    }

    public static BatchUserRolesResultVO toBatchUserRolesResultVO(BatchUserRolesResult r) {
        return new BatchUserRolesResultVO(r.users(), r.changedUsers(), r.grantsAdded());
    }

    public static DomainCopyResultVO toDomainCopyResultVO(DomainCopyResult r) {
        return new DomainCopyResultVO(r.permissionsCreated(), r.permissionsSkipped(), r.rolesCreated(), r.rolesSkipped());
    }

    public static SimulationVO toSimulationVO(SimulationResult r) {
        return new SimulationVO(
                r.eligible(), r.issues(), r.notices(),
                OrganizationVoMapper.toUserSummaryVO(r.user()),
                r.member(), r.applicationEnabled(), r.switchesDepartment(),
                r.roles().stream().map(role -> new SimulatedRoleVO(
                        role.id(), role.name(), role.code(), role.enabled(), role.expiresAt(), role.expired(), role.active())).toList(),
                r.permissions().stream().map(perm -> new SimulatedPermissionVO(
                        perm.id(), perm.code(), perm.name(), perm.resource(), perm.action(), perm.grantedBy())).toList(),
                r.decisions(), r.allowed());
    }

    public static DomainOverviewVO toDomainOverviewVO(DomainOverviewResult r) {
        return new DomainOverviewVO(r.applications(), r.enabledApplications(), r.departments(), r.users(),
                r.enabledUsers(), r.departmentMembers(), r.roles(), r.permissions(), r.members(),
                r.activeKeys(), r.activeContexts(), r.auditLast24h());
    }
}
