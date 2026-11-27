package com.melonhub.rbac.api.governance.vo;

import java.util.List;

/**
 * 权限使用者反查响应 VO。
 */
public record PermissionHoldersVO(
        String code,
        long applicationId,
        List<PermissionHolderVO> holders,
        long total,
        boolean truncated
) {}
