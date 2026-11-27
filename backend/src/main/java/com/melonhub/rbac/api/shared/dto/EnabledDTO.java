package com.melonhub.rbac.api.shared.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 启停状态切换请求 DTO。
 */
public record EnabledDTO(@NotNull Boolean enabled) {}
