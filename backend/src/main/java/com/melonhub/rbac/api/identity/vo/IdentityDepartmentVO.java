package com.melonhub.rbac.api.identity.vo;

/**
 * 统一身份组织架构部门项 VO。
 */
public record IdentityDepartmentVO(long id, String code, String name, Long parentId, boolean hasChildren) {}
