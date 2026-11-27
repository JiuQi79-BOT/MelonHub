package com.melonhub.rbac.api.governance.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 业务系统代员工提交角色申请请求 DTO。
 */
public record AccessRequestSubmitDTO(
        @NotBlank @Size(max = 100) String externalId,
        @Positive long departmentId,
        @Positive long roleId,
        @NotBlank @Size(max = 500) String reason,
        @Min(1) @Max(3650) Integer durationDays
) {}
