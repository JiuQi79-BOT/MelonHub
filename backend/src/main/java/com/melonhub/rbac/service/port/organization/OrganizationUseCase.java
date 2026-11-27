package com.melonhub.rbac.service.port.organization;

import com.melonhub.rbac.service.port.common.PageResult;
import com.melonhub.rbac.service.port.organization.OrganizationModels.DepartmentResult;
import com.melonhub.rbac.service.port.organization.OrganizationModels.DepartmentUserResult;
import com.melonhub.rbac.service.port.organization.OrganizationModels.SaveDepartmentCommand;
import com.melonhub.rbac.service.port.organization.OrganizationModels.SaveUserCommand;
import com.melonhub.rbac.service.port.organization.OrganizationModels.UserAccessResult;
import com.melonhub.rbac.service.port.organization.OrganizationModels.UserResult;
import com.melonhub.rbac.service.port.security.Visibility;
import java.util.Collection;
import java.util.List;

/**
 * 部门与用户生命周期管理用例端口。
 */
public interface OrganizationUseCase {
    List<DepartmentResult> departments(Collection<Long> visible);

    long department(SaveDepartmentCommand body, Long id, String actor);

    void deleteDepartment(long id, String actor);

    PageResult<UserResult> users(String keyword, Long department, Boolean enabled,
                                 Collection<Long> visibleDepartments, int page, int size);

    List<DepartmentUserResult> departmentUsers(long dept);

    default long user(SaveUserCommand body, Long id, String actor) {
        return user(body, id, actor, null);
    }

    long user(SaveUserCommand body, Long id, String actor, Collection<Long> scope);

    default void userStatus(long id, boolean enabled, String actor) {
        userStatus(id, enabled, actor, null);
    }

    void userStatus(long id, boolean enabled, String actor, Collection<Long> scope);

    void deleteUser(long id, String actor, Collection<Long> scope);

    String lockOwnedUser(long id, Collection<Long> scope);

    UserAccessResult userAccess(long id, Visibility visible);

    int revokeUserContexts(long id, String actor, Collection<Long> scope);
}
