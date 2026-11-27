package com.melonhub.rbac.api.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 统一身份批量导入单行用户凭据 DTO。
 */
public record IdentityUserRowDTO(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 128) String username,
        @NotBlank @Size(max = 256) String password,
        @Size(max = 150) String email,
        @NotNull @Size(max = 100) List<@NotNull @Positive Long> departmentIds
) {}
