package com.melonhub.rbac.api.application.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 调整业务软件每秒调用限额请求 DTO。
 */
public record RateLimitUpdateDTO(@Min(0) @Max(100000) Integer perSecond) {}
