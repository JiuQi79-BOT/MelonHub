package com.melonhub.rbac.api.domainrbac.dto;

import jakarta.validation.constraints.Positive;
import java.time.OffsetDateTime;

/**
 * 逐角色到期时间覆写项 DTO。
 */
public record RoleExpiryDTO(@Positive long roleId, OffsetDateTime expiresAt) {}
