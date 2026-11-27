package com.melonhub.rbac.api.identity.vo;

/**
 * 统一身份单个用户查询响应 VO。
 */
public record IdentityUserLookupVO(String userId, String no, String name) {}
