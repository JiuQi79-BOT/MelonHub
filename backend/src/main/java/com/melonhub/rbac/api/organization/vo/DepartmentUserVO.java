package com.melonhub.rbac.api.organization.vo;

/**
 * 部门成员简要信息响应 VO。
 */
public record DepartmentUserVO(long id, String name, String externalId, boolean enabled) {}
