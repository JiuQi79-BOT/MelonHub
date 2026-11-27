package com.melonhub.rbac.api.domainrbac.dto;

import com.melonhub.rbac.api.shared.dto.ValidationPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新建或修改隔离域角色请求 DTO。
 */
public record RoleDTO(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Pattern(regexp = ValidationPatterns.DOMAIN_CODE) String code,
        @Size(max = 500) String description
) {}
