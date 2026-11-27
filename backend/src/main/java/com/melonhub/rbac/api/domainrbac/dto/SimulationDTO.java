package com.melonhub.rbac.api.domainrbac.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 权限模拟诊断请求 DTO。
 */
public record SimulationDTO(
        @Positive long userId,
        @Positive long departmentId,
        @Positive long applicationId,
        @Size(max = 1000) List<@NotNull @Positive Long> activeRoleIds,
        @Size(max = 100) List<@NotBlank @Size(max = 100) String> permissions
) {}
