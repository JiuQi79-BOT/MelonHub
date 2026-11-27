package com.melonhub.rbac.service.port.domainrbac;

import com.melonhub.rbac.service.port.common.PageResult;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.BatchUserRolesCommand;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.BatchUserRolesResult;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.DomainAssignmentResult;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.DomainCopyCommand;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.DomainCopyResult;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.DomainOverviewResult;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.PermissionResult;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.RoleExpiryCommand;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.RoleMemberResult;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.RoleResult;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.SavePermissionCommand;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.SaveRoleCommand;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.SimulationCommand;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.SimulationResult;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.UserRoleAssignmentsResult;
import java.util.List;

/**
 * 隔离域（部门 × 软件）内 RBAC1 角色、权限、继承、授权分配、复制与诊断用例端口。
 */
public interface DomainRbacUseCase {
    void domain(long dept, long app);

    List<RoleResult> roles(long dept, long app);

    long role(SaveRoleCommand body, Long id, long dept, long app, String actor);

    void roleStatus(long id, long dept, long app, boolean enabled, String actor);

    void deleteRole(long id, long dept, long app, String actor);

    void roleParents(long role, long dept, long app, List<Long> parents, String actor);

    List<PermissionResult> permissions(long dept, long app);

    long permission(SavePermissionCommand body, Long id, long dept, long app, String actor);

    void deletePermission(long id, long dept, long app, String actor);

    void rolePermissions(long role, long dept, long app, List<Long> permissions, String actor);

    List<RoleMemberResult> roleMembers(long role, long dept, long app);

    void roleMembers(long role, long dept, long app, List<Long> users, String actor);

    UserRoleAssignmentsResult assignments(long user, long dept, long app);

    PageResult<DomainAssignmentResult> domainAssignments(long dept, long app, String keyword, int page, int size);

    BatchUserRolesResult addUserRoles(long dept, long app, BatchUserRolesCommand body, String actor);

    default void userRoles(long user, long dept, long app, List<Long> roles, String actor) {
        userRoles(user, dept, app, roles, null, null, actor);
    }

    default void userRoles(long user, long dept, long app, List<Long> roles, Integer expiresInDays, String actor) {
        userRoles(user, dept, app, roles, expiresInDays, null, actor);
    }

    void userRoles(long user, long dept, long app, List<Long> roles, Integer expiresInDays,
                   List<RoleExpiryCommand> expiries, String actor);

    DomainCopyResult copyDomain(DomainCopyCommand body, String actor);

    SimulationResult simulate(SimulationCommand body);

    DomainOverviewResult overview(long dept, long app);
}
