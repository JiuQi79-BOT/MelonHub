package com.melonhub.rbac.api.governance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 员工撤回本人待审批角色申请请求 DTO。
 */
public record AccessRequestCancelDTO(@NotBlank @Size(max = 100) String externalId) {}
