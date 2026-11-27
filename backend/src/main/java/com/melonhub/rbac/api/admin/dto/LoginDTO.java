package com.melonhub.rbac.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 控制台管理员登录请求 DTO。
 */
public record LoginDTO(
        @NotBlank @Size(max = 100) String username,
        @NotBlank @Size(max = 100) String password,
        @Size(max = 12) String mfaCode
) {
    public LoginDTO(String username, String password) {
        this(username, password, null);
    }
}
