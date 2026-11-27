package com.melonhub.rbac.api.governance;

import com.melonhub.rbac.api.governance.dto.ManifestDTO;
import com.melonhub.rbac.api.governance.vo.AccessRequestVO;
import com.melonhub.rbac.api.governance.vo.ExpiringAssignmentVO;
import com.melonhub.rbac.api.governance.vo.ManifestApplicationVO;
import com.melonhub.rbac.api.governance.vo.ManifestDepartmentVO;
import com.melonhub.rbac.api.governance.vo.ManifestImportResultVO;
import com.melonhub.rbac.api.governance.vo.ManifestPermissionVO;
import com.melonhub.rbac.api.governance.vo.ManifestRoleVO;
import com.melonhub.rbac.api.governance.vo.PermissionHolderRoleVO;
import com.melonhub.rbac.api.governance.vo.PermissionHolderVO;
import com.melonhub.rbac.api.governance.vo.PermissionHoldersVO;
import com.melonhub.rbac.api.governance.vo.PermissionManifestVO;
import com.melonhub.rbac.api.governance.vo.ReviewDetailVO;
import com.melonhub.rbac.api.governance.vo.ReviewItemVO;
import com.melonhub.rbac.api.governance.vo.ReviewSummaryVO;
import com.melonhub.rbac.api.governance.vo.SelfAccessRoleVO;
import com.melonhub.rbac.api.governance.vo.SelfAccessVO;
import com.melonhub.rbac.api.shared.vo.PageVO;
import com.melonhub.rbac.service.port.common.PageResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.AccessRequestResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ExpiringAssignmentResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ManifestDepartmentCommand;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ManifestImportCommand;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ManifestImportResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ManifestPermissionCommand;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ManifestRoleCommand;
import com.melonhub.rbac.service.port.governance.GovernanceModels.PermissionHoldersResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.PermissionManifestResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ReviewDetailResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ReviewItemResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ReviewSummaryResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.SelfAccessResult;

/**
 * 授权治理模块 DTO/Command 与 Result/VO 映射器。
 */
public final class GovernanceVoMapper {
    private GovernanceVoMapper() {}

    public static AccessRequestVO toAccessRequestVO(AccessRequestResult r) {
        return new AccessRequestVO(
                r.id(), r.userId(), r.userName(), r.externalId(), r.departmentId(), r.departmentName(),
                r.applicationId(), r.applicationName(), r.roleId(), r.roleName(), r.roleCode(), r.roleEnabled(),
                r.reason(), r.durationDays(), r.status(), r.requestedBy(), r.decidedBy(), r.decidedAt(),
                r.decisionComment(), r.grantExpiresAt(), r.createdAt());
    }

    public static PageVO<AccessRequestVO> toAccessRequestPageVO(PageResult<AccessRequestResult> p) {
        return new PageVO<>(p.items().stream().map(GovernanceVoMapper::toAccessRequestVO).toList(),
                p.total(), p.page(), p.size());
    }

    public static SelfAccessVO toSelfAccessVO(SelfAccessResult r) {
        return new SelfAccessVO(r.userId(), r.departmentId(), r.applicationId(),
                r.roles().stream().map(role -> new SelfAccessRoleVO(
                        role.id(), role.name(), role.code(), role.enabled(), role.expiresAt())).toList(),
                r.permissions());
    }

    public static ReviewSummaryVO toReviewSummaryVO(ReviewSummaryResult r) {
        return new ReviewSummaryVO(
                r.id(), r.name(), r.applicationId(), r.applicationName(), r.departmentId(), r.departmentName(),
                r.status(), r.dueAt(), r.createdBy(), r.createdAt(), r.closedBy(), r.closedAt(),
                r.items(), r.pending(), r.kept(), r.revoked(), r.skipped());
    }

    public static ReviewDetailVO toReviewDetailVO(ReviewDetailResult r) {
        return new ReviewDetailVO(
                r.id(), r.name(), r.applicationId(), r.applicationName(), r.departmentId(), r.departmentName(),
                r.status(), r.dueAt(), r.createdBy(), r.createdAt(), r.closedBy(), r.closedAt(), r.summary(),
                r.items(), r.pending(), r.kept(), r.revoked(), r.skipped());
    }

    public static PageVO<ReviewItemVO> toReviewItemPageVO(PageResult<ReviewItemResult> p) {
        return new PageVO<>(p.items().stream().map(i -> new ReviewItemVO(
                i.id(), i.userId(), i.userName(), i.externalId(), i.roleId(), i.roleName(), i.roleCode(),
                i.departmentId(), i.departmentName(), i.decision(), i.comment(), i.decidedBy(), i.decidedAt(),
                i.applied(), i.skipped())).toList(), p.total(), p.page(), p.size());
    }

    public static PermissionManifestVO toManifestVO(PermissionManifestResult r) {
        return new PermissionManifestVO(
                r.version(),
                new ManifestApplicationVO(r.application().code(), r.application().name()),
                r.exportedAt(),
                r.departments().stream().map(d -> new ManifestDepartmentVO(
                        d.code(), d.name(),
                        d.permissions().stream().map(p -> new ManifestPermissionVO(p.code(), p.name(), p.resource(), p.action())).toList(),
                        d.roles().stream().map(role -> new ManifestRoleVO(role.code(), role.name(), role.description(), role.enabled(), role.permissions())).toList()
                )).toList());
    }

    public static ManifestImportCommand toManifestCommand(ManifestDTO dto) {
        return new ManifestImportCommand(
                dto.version(),
                dto.departments().stream().map(d -> new ManifestDepartmentCommand(
                        d.code(),
                        d.permissions().stream().map(p -> new ManifestPermissionCommand(p.code(), p.name(), p.resource(), p.action())).toList(),
                        d.roles().stream().map(role -> new ManifestRoleCommand(role.code(), role.name(), role.description(), role.enabled(), role.permissions())).toList()
                )).toList());
    }

    public static ManifestImportResultVO toManifestImportResultVO(ManifestImportResult r) {
        return new ManifestImportResultVO(r.permissionsCreated(), r.permissionsUpdated(), r.rolesCreated(), r.rolesUpdated());
    }

    public static PermissionHoldersVO toPermissionHoldersVO(PermissionHoldersResult r) {
        return new PermissionHoldersVO(
                r.code(), r.applicationId(),
                r.holders().stream().map(h -> new PermissionHolderVO(
                        h.userId(), h.userName(), h.externalId(), h.departmentId(), h.departmentName(),
                        h.roles().stream().map(role -> new PermissionHolderRoleVO(role.id(), role.name(), role.code(), role.expiresAt())).toList()
                )).toList(),
                r.total(), r.truncated());
    }

    public static ExpiringAssignmentVO toExpiringAssignmentVO(ExpiringAssignmentResult r) {
        return new ExpiringAssignmentVO(r.userId(), r.userName(), r.externalId(), r.roleId(), r.roleName(),
                r.roleCode(), r.departmentId(), r.departmentName(), r.applicationId(), r.applicationName(),
                r.expiresAt(), r.grantedBy(), r.grantedAt());
    }
}
