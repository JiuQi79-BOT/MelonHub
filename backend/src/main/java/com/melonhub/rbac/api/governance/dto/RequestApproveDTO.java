package com.melonhub.rbac.api.governance.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 管理员审批通过角色申请请求 DTO。
 */
public record RequestApproveDTO(
        @Min(1) @Max(3650) Integer durationDays,
        @Size(max = 500) String comment,
        Boolean permanent
) {
    public RequestApproveDTO(Integer durationDays, String comment) {
        this(durationDays, comment, null);
    }
}
