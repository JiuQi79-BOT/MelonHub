package com.melonhub.rbac.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 绑定确认两步验证动态口令 DTO。
 */
public record MfaCodeDTO(@NotBlank @Pattern(regexp = "\\d{6}") String code) {}
