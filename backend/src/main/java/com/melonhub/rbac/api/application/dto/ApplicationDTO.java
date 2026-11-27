package com.melonhub.rbac.api.application.dto;

import com.melonhub.rbac.api.shared.dto.ValidationPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新建或修改业务软件请求 DTO。
 */
public record ApplicationDTO(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Pattern(regexp = ValidationPatterns.CODE) String code,
        @Size(max = 500) String description,
        @Size(max = 100) String owner
) {
    public ApplicationDTO(String name, String code, String description) {
        this(name, code, description, null);
    }
}
