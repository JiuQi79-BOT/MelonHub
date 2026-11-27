package com.melonhub.rbac.api.organization;

import com.melonhub.rbac.api.organization.vo.AccessDomainRoleVO;
import com.melonhub.rbac.api.organization.vo.AccessDomainVO;
import com.melonhub.rbac.api.organization.vo.ActiveContextVO;
import com.melonhub.rbac.api.organization.vo.DepartmentUserVO;
import com.melonhub.rbac.api.organization.vo.DepartmentVO;
import com.melonhub.rbac.api.organization.vo.UserAccessVO;
import com.melonhub.rbac.api.organization.vo.UserImportFailureVO;
import com.melonhub.rbac.api.organization.vo.UserImportResultVO;
import com.melonhub.rbac.api.organization.vo.UserSummaryVO;
import com.melonhub.rbac.api.organization.vo.UserVO;
import com.melonhub.rbac.api.shared.vo.PageVO;
import com.melonhub.rbac.service.port.common.PageResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.UserImportResult;
import com.melonhub.rbac.service.port.organization.OrganizationModels.DepartmentResult;
import com.melonhub.rbac.service.port.organization.OrganizationModels.DepartmentUserResult;
import com.melonhub.rbac.service.port.organization.OrganizationModels.UserAccessResult;
import com.melonhub.rbac.service.port.organization.OrganizationModels.UserResult;
import com.melonhub.rbac.service.port.organization.OrganizationModels.UserSummaryResult;

/**
 * 组织架构模块应用层结果与 HTTP VO 映射器。
 */
public final class OrganizationVoMapper {
    private OrganizationVoMapper() {}

    public static DepartmentVO toDepartmentVO(DepartmentResult r) {
        return new DepartmentVO(r.id(), r.name(), r.code(), r.createdAt(), r.memberCount(), r.roleCount());
    }

    public static DepartmentUserVO toDepartmentUserVO(DepartmentUserResult r) {
        return new DepartmentUserVO(r.id(), r.name(), r.externalId(), r.enabled());
    }

    public static UserSummaryVO toUserSummaryVO(UserSummaryResult r) {
        return new UserSummaryVO(r.id(), r.name(), r.externalId(), r.email(), r.enabled(),
                r.activeDepartmentId(), r.contextVersion(), r.createdAt());
    }

    public static UserVO toUserVO(UserResult r) {
        return new UserVO(r.id(), r.name(), r.externalId(), r.email(), r.enabled(),
                r.activeDepartmentId(), r.contextVersion(), r.createdAt(), r.departmentIds());
    }

    public static PageVO<UserVO> toUserPageVO(PageResult<UserResult> page) {
        return new PageVO<>(page.items().stream().map(OrganizationVoMapper::toUserVO).toList(),
                page.total(), page.page(), page.size());
    }

    public static UserAccessVO toUserAccessVO(UserAccessResult r) {
        return new UserAccessVO(
                toUserVO(r.user()),
                r.domains().stream().map(d -> new AccessDomainVO(
                        d.departmentId(), d.departmentName(), d.applicationId(), d.applicationName(), d.applicationEnabled(),
                        d.roles().stream().map(role -> new AccessDomainRoleVO(
                                role.id(), role.name(), role.code(), role.enabled(), role.expiresAt(), role.grantedBy())).toList(),
                        d.permissions())).toList(),
                r.contexts().stream().map(c -> new ActiveContextVO(
                        c.departmentId(), c.departmentName(), c.applicationId(), c.applicationName(),
                        c.createdAt(), c.expiresAt(), c.activeRoleCount(), c.valid())).toList());
    }

    public static UserImportResultVO toUserImportResultVO(UserImportResult r) {
        return new UserImportResultVO(r.created(), r.updated(),
                r.failed().stream().map(f -> new UserImportFailureVO(f.line(), f.externalId(), f.error())).toList());
    }
}
