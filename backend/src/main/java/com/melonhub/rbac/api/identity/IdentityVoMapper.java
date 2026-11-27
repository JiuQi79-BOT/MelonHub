package com.melonhub.rbac.api.identity;

import com.melonhub.rbac.api.identity.vo.CourseAuditActorVO;
import com.melonhub.rbac.api.identity.vo.CourseAuditEventVO;
import com.melonhub.rbac.api.identity.vo.IdentityApiPermissionCheckVO;
import com.melonhub.rbac.api.identity.vo.IdentityCurrentUserVO;
import com.melonhub.rbac.api.identity.vo.IdentityDepartmentUserVO;
import com.melonhub.rbac.api.identity.vo.IdentityDepartmentVO;
import com.melonhub.rbac.api.identity.vo.IdentityLoginVO;
import com.melonhub.rbac.api.identity.vo.IdentityPageVO;
import com.melonhub.rbac.api.identity.vo.IdentityPermissionCheckVO;
import com.melonhub.rbac.api.identity.vo.IdentityUserLookupVO;
import com.melonhub.rbac.service.port.identity.IdentityModels.CourseAuditEventResult;
import com.melonhub.rbac.service.port.identity.IdentityModels.IdentityApiPermissionCheckResult;
import com.melonhub.rbac.service.port.identity.IdentityModels.IdentityCurrentUserResult;
import com.melonhub.rbac.service.port.identity.IdentityModels.IdentityDepartmentResult;
import com.melonhub.rbac.service.port.identity.IdentityModels.IdentityDepartmentUserResult;
import com.melonhub.rbac.service.port.identity.IdentityModels.IdentityLoginResult;
import com.melonhub.rbac.service.port.identity.IdentityModels.IdentityPageResult;
import com.melonhub.rbac.service.port.identity.IdentityModels.IdentityPermissionCheckResult;
import com.melonhub.rbac.service.port.identity.IdentityModels.IdentityUserLookupResult;

/**
 * 统一身份模块 Result 与 VO 映射器。
 */
public final class IdentityVoMapper {
    private IdentityVoMapper() {}

    public static IdentityLoginVO toLoginVO(IdentityLoginResult r) {
        return new IdentityLoginVO(r.accessToken(), r.tokenType(), r.expiresIn());
    }

    public static IdentityCurrentUserVO toCurrentUserVO(IdentityCurrentUserResult r) {
        return new IdentityCurrentUserVO(r.subject(), r.roles(), r.permissions(), r.name(), r.no());
    }

    public static IdentityPageVO<CourseAuditEventVO> toCourseAuditPageVO(IdentityPageResult<CourseAuditEventResult> p) {
        return new IdentityPageVO<>(p.page(), p.pageSize(), p.total(),
                p.items().stream().map(e -> new CourseAuditEventVO(e.id(), e.at(),
                        new CourseAuditActorVO(e.actor().id(), e.actor().name(), e.actor().roles(), e.actor().deleted()),
                        e.type(), e.target())).toList());
    }

    public static IdentityPageVO<IdentityDepartmentVO> toDepartmentPageVO(IdentityPageResult<IdentityDepartmentResult> p) {
        return new IdentityPageVO<>(p.page(), p.pageSize(), p.total(),
                p.items().stream().map(d -> new IdentityDepartmentVO(d.id(), d.code(), d.name(), d.parentId(), d.hasChildren())).toList());
    }

    public static IdentityPageVO<IdentityDepartmentUserVO> toDepartmentUserPageVO(IdentityPageResult<IdentityDepartmentUserResult> p) {
        return new IdentityPageVO<>(p.page(), p.pageSize(), p.total(),
                p.items().stream().map(u -> new IdentityDepartmentUserVO(u.userId(), u.no(), u.name(), u.departmentId(), u.departmentName())).toList());
    }

    public static IdentityPermissionCheckVO toPermissionCheckVO(IdentityPermissionCheckResult r) {
        return new IdentityPermissionCheckVO(r.permission(), r.allowed());
    }

    public static IdentityApiPermissionCheckVO toApiPermissionCheckVO(IdentityApiPermissionCheckResult r) {
        return new IdentityApiPermissionCheckVO(r.operationId(), r.allowed());
    }

    public static IdentityUserLookupVO toUserLookupVO(IdentityUserLookupResult r) {
        return new IdentityUserLookupVO(r.userId(), r.no(), r.name());
    }
}
