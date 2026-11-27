package com.melonhub.rbac.api.governance.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 权限清单导入请求 DTO。
 */
public record ManifestDTO(
        @Positive Integer version,
        @NotNull @Size(max = 200) List<@Valid ManifestDepartmentDTO> departments
) {}
