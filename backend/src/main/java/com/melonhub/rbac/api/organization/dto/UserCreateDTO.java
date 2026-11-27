package com.melonhub.rbac.api.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 新建用户请求 DTO（可选附带统一身份初始密码）。
 */
public record UserCreateDTO(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 100) String externalId,
        @Size(max = 150) String email,
        @NotNull @Size(max = 100) List<@NotNull @Positive Long> departmentIds,
        @Size(min = 8, max = 72) String identityPassword
) {}
