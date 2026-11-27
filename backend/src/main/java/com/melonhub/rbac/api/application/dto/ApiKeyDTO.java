package com.melonhub.rbac.api.application.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新建软件 API Key 请求 DTO。
 */
public record ApiKeyDTO(
        @NotBlank @Size(max = 100) String name,
        @Min(1) @Max(3650) Integer expiresInDays
) {}
