package com.melonhub.rbac.api.governance.dto;

import com.melonhub.rbac.api.shared.dto.ValidationPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 权限清单导入项中的权限定义 DTO。
 */
public record ManifestPermissionDTO(
        @NotBlank @Pattern(regexp = ValidationPatterns.DOMAIN_CODE) String code,
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 100) String resource,
        @NotBlank @Size(max = 64) String action
) {}
