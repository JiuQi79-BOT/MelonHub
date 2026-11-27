package com.melonhub.rbac.api.governance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 复核条目决定（KEEP / REVOKE）请求 DTO。
 */
public record ReviewItemDecisionDTO(
        @NotBlank @Pattern(regexp = "KEEP|REVOKE") String decision,
        @Size(max = 500) String comment
) {}
