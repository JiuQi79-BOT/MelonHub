package com.melonhub.rbac.api.governance.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 发起定期权限复核请求 DTO。
 */
public record ReviewCreateDTO(
        @NotBlank @Size(max = 100) String name,
        @Positive long applicationId,
        @Positive Long departmentId,
        @Min(1) @Max(365) Integer dueDays
) {}
