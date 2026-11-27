package com.melonhub.rbac.api.domainrbac.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.melonhub.rbac.api.organization.vo.UserSummaryVO;
import java.util.List;
import java.util.Map;

/**
 * 权限模拟诊断结果响应 VO。
 */
public record SimulationVO(
        boolean eligible,
        List<String> issues,
        List<String> notices,
        UserSummaryVO user,
        boolean member,
        boolean applicationEnabled,
        boolean switchesDepartment,
        List<SimulatedRoleVO> roles,
        List<SimulatedPermissionVO> permissions,
        @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Boolean> decisions,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean allowed
) {}
