package com.melonhub.rbac.api.console.vo;

import java.util.List;

/**
 * 控制台全局检索聚合响应 VO。
 */
public record SearchResultsVO(
        List<SearchUserVO> users,
        List<SearchDepartmentVO> departments,
        List<SearchApplicationVO> applications,
        List<SearchRoleVO> roles
) {}
