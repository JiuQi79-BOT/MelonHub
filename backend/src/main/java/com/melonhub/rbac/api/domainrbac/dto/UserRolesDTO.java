package com.melonhub.rbac.api.domainrbac.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 覆盖用户在单个隔离域内的完整角色集合及期限请求 DTO。
 */
public record UserRolesDTO(
        @NotNull @Size(max = 1000) List<@NotNull @Positive Long> ids,
        @Min(1) @Max(3650) Integer expiresInDays,
        @Size(max = 1000) List<@NotNull @Valid RoleExpiryDTO> expiries
) {
    public UserRolesDTO(List<Long> ids) {
        this(ids, null, null);
    }

    public UserRolesDTO(List<Long> ids, Integer expiresInDays) {
        this(ids, expiresInDays, null);
    }
}
