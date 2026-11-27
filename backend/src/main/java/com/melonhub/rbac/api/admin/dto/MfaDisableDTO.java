package com.melonhub.rbac.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 停用两步验证请求 DTO（需校验当前密码与动态口令）。
 */
public record MfaDisableDTO(
        @NotBlank @Size(max = 100) String password,
        @NotBlank @Pattern(regexp = "\\d{6}") String code
) {}
