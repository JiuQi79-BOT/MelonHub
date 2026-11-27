package com.melonhub.rbac.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 上级管理员重置下级管理员密码请求 DTO。
 */
public record PasswordResetDTO(@NotBlank @Size(min = 12, max = 100) String password) {}
