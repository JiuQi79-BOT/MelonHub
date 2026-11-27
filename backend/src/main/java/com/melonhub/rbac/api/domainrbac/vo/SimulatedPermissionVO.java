package com.melonhub.rbac.api.domainrbac.vo;

import java.util.List;

/**
 * 权限诊断中的生效权限来源 VO。
 */
public record SimulatedPermissionVO(
        long id,
        String code,
        String name,
        String resource,
        String action,
        List<Long> grantedBy
) {}
