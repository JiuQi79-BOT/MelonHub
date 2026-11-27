package com.melonhub.rbac.api.admin.dto;

import com.melonhub.rbac.api.shared.dto.ValidationPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 新建管理员账号请求 DTO。
 */
public record AccountCreateDTO(
        @NotBlank @Pattern(regexp = ValidationPatterns.USERNAME) String username,
        @Size(max = 100) String displayName,
        @NotBlank @Pattern(regexp = ValidationPatterns.ADMIN_ROLE_CODE) String role,
        @NotBlank @Size(min = 12, max = 100) String password,
        Boolean enabled,
        @Size(max = 200) List<@NotNull @Positive Long> applicationIds,
        Boolean allApplications,
        @Positive Long parentId,
        @Pattern(regexp = ValidationPatterns.USERNAME) String parentUsername,
        @Pattern(regexp = ValidationPatterns.ADMIN_KIND) String kind,
        Boolean allDepartments,
        @Size(max = 200) List<@NotNull @Positive Long> departmentIds
) {
    public AccountCreateDTO(String username, String displayName, String role, String password,
                            Boolean enabled, List<Long> applicationIds) {
        this(username, displayName, role, password, enabled, applicationIds, null, null, null, null, null, null);
    }
}
