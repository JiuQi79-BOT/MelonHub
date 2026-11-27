package com.melonhub.rbac.api.organization.dto;

import com.melonhub.rbac.api.shared.dto.ValidationPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新建或修改部门请求 DTO。
 */
public record DepartmentDTO(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Pattern(regexp = ValidationPatterns.CODE) String code
) {}
