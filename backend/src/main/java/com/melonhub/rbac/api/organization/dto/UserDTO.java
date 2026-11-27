package com.melonhub.rbac.api.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 修改用户资料与所属部门请求 DTO。
 */
public record UserDTO(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 100) String externalId,
        @Size(max = 150) String email,
        @NotNull @Size(max = 100) List<@NotNull @Positive Long> departmentIds
) {}
