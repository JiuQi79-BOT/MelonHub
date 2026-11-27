package com.melonhub.rbac.api.shared.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 主键集合提交请求 DTO（用于角色权限、父角色继承及角色成员维护）。
 */
public record IdsDTO(@NotNull @Size(max = 1000) List<@NotNull @Positive Long> ids) {}
