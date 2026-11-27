package com.melonhub.rbac.api.domainrbac.dto;

import jakarta.validation.constraints.Positive;

/**
 * 跨部门复制同软件角色与权限请求 DTO。
 */
public record DomainCopyDTO(
        @Positive long applicationId,
        @Positive long sourceDepartmentId,
        @Positive long targetDepartmentId
) {}
