package com.melonhub.rbac.api.console.vo;

/**
 * 全局检索用户结果项 VO。
 */
public record SearchUserVO(long id, String name, String externalId, boolean enabled) {}
