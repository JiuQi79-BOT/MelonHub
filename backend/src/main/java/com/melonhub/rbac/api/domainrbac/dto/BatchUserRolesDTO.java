package com.melonhub.rbac.api.domainrbac.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 多用户多角色批量追加授权请求 DTO。
 */
public record BatchUserRolesDTO(
        @NotNull @Size(min = 1, max = 1000) List<@NotNull @Positive Long> userIds,
        @NotNull @Size(min = 1, max = 1000) List<@NotNull @Positive Long> roleIds,
        @Min(1) @Max(3650) Integer expiresInDays
) {}
