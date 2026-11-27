package com.melonhub.rbac.api.identity.vo;

/**
 * 统一身份部门成员项 VO。
 */
public record IdentityDepartmentUserVO(String userId, String no, String name, long departmentId, String departmentName) {}
