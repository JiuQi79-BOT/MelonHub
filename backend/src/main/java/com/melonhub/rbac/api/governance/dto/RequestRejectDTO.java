package com.melonhub.rbac.api.governance.dto;

import jakarta.validation.constraints.Size;

/**
 * 管理员驳回角色申请请求 DTO。
 */
public record RequestRejectDTO(@Size(max = 500) String comment) {}
