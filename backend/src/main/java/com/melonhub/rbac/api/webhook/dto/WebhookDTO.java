package com.melonhub.rbac.api.webhook.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 新建或修改 Webhook 订阅请求 DTO。
 */
public record WebhookDTO(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 500) @Pattern(regexp = "https?://\\S+") String url,
        @Size(min = 16, max = 100) @Pattern(regexp = "[!-~][ -~]{14,98}[!-~]") String secret,
        @NotNull @Size(min = 1, max = 50) List<@NotBlank @Size(max = 64) @Pattern(regexp = "\\*|[a-z0-9_.-]{1,64}") String> events,
        Boolean enabled
) {}
