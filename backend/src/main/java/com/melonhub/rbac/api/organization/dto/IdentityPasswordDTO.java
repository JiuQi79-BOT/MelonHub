package com.melonhub.rbac.api.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 重置用户统一身份密码请求 DTO。
 */
public record IdentityPasswordDTO(@NotBlank @Size(min = 8, max = 72) String password) {}
