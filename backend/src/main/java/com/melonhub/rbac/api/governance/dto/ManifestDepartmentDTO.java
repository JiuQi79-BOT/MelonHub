package com.melonhub.rbac.api.governance.dto;

import com.melonhub.rbac.api.shared.dto.ValidationPatterns;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 权限清单导入项中的单个部门配置节 DTO。
 */
public record ManifestDepartmentDTO(
        @NotBlank @Pattern(regexp = ValidationPatterns.CODE) String code,
        @NotNull @Size(max = 2000) List<@Valid ManifestPermissionDTO> permissions,
        @NotNull @Size(max = 2000) List<@Valid ManifestRoleDTO> roles
) {}
