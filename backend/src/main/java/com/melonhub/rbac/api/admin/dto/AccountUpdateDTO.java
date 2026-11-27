package com.melonhub.rbac.api.admin.dto;

import com.melonhub.rbac.api.shared.dto.ValidationPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 修改管理员账号角色、启停与双轴管理范围请求 DTO。
 */
public record AccountUpdateDTO(
        @Size(max = 100) String displayName,
        @NotBlank @Pattern(regexp = ValidationPatterns.ADMIN_ROLE_CODE) String role,
        @NotNull Boolean enabled,
        @Size(max = 200) List<@NotNull @Positive Long> applicationIds,
        Boolean allApplications,
        @Positive Long parentId,
        @Pattern(regexp = ValidationPatterns.USERNAME) String parentUsername,
        @Pattern(regexp = ValidationPatterns.ADMIN_KIND) String kind,
        Boolean allDepartments,
        @Size(max = 200) List<@NotNull @Positive Long> departmentIds
) {
    public AccountUpdateDTO(String displayName, String role, Boolean enabled, List<Long> applicationIds) {
        this(displayName, role, enabled, applicationIds, null, null, null, null, null, null);
    }
}
