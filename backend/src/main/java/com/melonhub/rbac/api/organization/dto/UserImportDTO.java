package com.melonhub.rbac.api.organization.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 批量导入用户请求 DTO。
 */
public record UserImportDTO(@NotNull @Size(min = 1, max = 500) List<UserImportRowDTO> users) {}
