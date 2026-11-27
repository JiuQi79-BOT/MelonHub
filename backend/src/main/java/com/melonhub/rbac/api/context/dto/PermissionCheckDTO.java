package com.melonhub.rbac.api.context.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 业务系统批量权限校验请求 DTO。
 */
public record PermissionCheckDTO(@NotNull @Size(min = 1, max = 100) List<@NotBlank @Size(max = 100) String> permissions) {}
