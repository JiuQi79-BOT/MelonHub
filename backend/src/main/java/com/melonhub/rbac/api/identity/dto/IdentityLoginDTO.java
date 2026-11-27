package com.melonhub.rbac.api.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 课程系统统一身份登录请求 DTO。
 */
public record IdentityLoginDTO(
        @NotBlank @Size(max = 128) String username,
        @NotBlank @Size(max = 256) String password,
        @NotNull @Size(min = 1, max = 20) List<@NotBlank @Size(max = 128) String> systems
) {}
