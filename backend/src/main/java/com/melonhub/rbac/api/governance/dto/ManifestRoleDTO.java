package com.melonhub.rbac.api.governance.dto;

import com.melonhub.rbac.api.shared.dto.ValidationPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 权限清单导入项中的角色定义 DTO。
 */
public record ManifestRoleDTO(
        @NotBlank @Pattern(regexp = ValidationPatterns.DOMAIN_CODE) String code,
        @NotBlank @Size(max = 100) String name,
        @Size(max = 500) String description,
        Boolean enabled,
        @NotNull @Size(max = 1000) List<@NotBlank @Size(max = 100) String> permissions
) {}
