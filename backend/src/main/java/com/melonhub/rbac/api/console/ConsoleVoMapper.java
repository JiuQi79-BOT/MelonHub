package com.melonhub.rbac.api.console;

import com.melonhub.rbac.api.audit.AuditVoMapper;
import com.melonhub.rbac.api.console.vo.ConsoleCountsVO;
import com.melonhub.rbac.api.console.vo.HealthVO;
import com.melonhub.rbac.api.console.vo.SearchApplicationVO;
import com.melonhub.rbac.api.console.vo.SearchDepartmentVO;
import com.melonhub.rbac.api.console.vo.SearchResultsVO;
import com.melonhub.rbac.api.console.vo.SearchRoleVO;
import com.melonhub.rbac.api.console.vo.SearchUserVO;
import com.melonhub.rbac.api.console.vo.SystemStatusVO;
import com.melonhub.rbac.service.port.console.ConsoleModels.HealthResult;
import com.melonhub.rbac.service.port.console.ConsoleModels.SearchResultsResult;
import com.melonhub.rbac.service.port.console.ConsoleModels.SystemStatusResult;

/**
 * 控制台健康探针、全局检索与状态看板模块 Result 与 VO 映射器。
 */
public final class ConsoleVoMapper {
    private ConsoleVoMapper() {}

    public static HealthVO toHealthVO(HealthResult r) {
        return new HealthVO(r.status(), r.database(), r.cache(), r.model());
    }

    public static SearchResultsVO toSearchResultsVO(SearchResultsResult r) {
        return new SearchResultsVO(
                r.users().stream().map(u -> new SearchUserVO(u.id(), u.name(), u.externalId(), u.enabled())).toList(),
                r.departments().stream().map(d -> new SearchDepartmentVO(d.id(), d.name(), d.code())).toList(),
                r.applications().stream().map(a -> new SearchApplicationVO(a.id(), a.name(), a.code(), a.enabled())).toList(),
                r.roles().stream().map(role -> new SearchRoleVO(role.id(), role.name(), role.code(),
                        role.departmentId(), role.departmentName(), role.applicationId(), role.applicationName())).toList());
    }

    public static SystemStatusVO toSystemStatusVO(SystemStatusResult r) {
        ConsoleCountsVO counts = r.counts() == null ? null : new ConsoleCountsVO(
                r.counts().users(), r.counts().enabledUsers(), r.counts().departments(),
                r.counts().applications(), r.counts().enabledApplications(), r.counts().roles(),
                r.counts().permissions(), r.counts().assignments(), r.counts().activeContexts());
        return new SystemStatusVO(
                r.version(), r.uptimeSeconds(), r.javaVersion(), r.cache(), r.database(), r.databaseLatencyMs(),
                counts, r.pendingRequests(), r.openReviews(), r.webhookBacklog(), r.expiringIn7Days(),
                AuditVoMapper.toAuditSealStateVO(r.audit()));
    }
}
