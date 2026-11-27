package com.melonhub.rbac.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 控制台管理员自主修改密码请求 DTO。
 */
public record PasswordChangeDTO(
        @NotBlank @Size(max = 100) String currentPassword,
        @NotBlank @Size(min = 12, max = 100) String newPassword
) {}
