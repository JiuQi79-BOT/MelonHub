package com.melonhub.rbac.api.admin.dto;

import com.melonhub.rbac.api.shared.dto.ValidationPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 新建自定义管理员角色请求 DTO。
 */
public record AdminRoleCreateDTO(
        @NotBlank @Pattern(regexp = ValidationPatterns.ADMIN_ROLE_CODE) String code,
        @NotBlank @Size(max = 100) String name,
        @Size(max = 500) String description,
        @NotNull @Size(max = 50) List<@NotBlank @Size(max = 64) String> functions
) {}
