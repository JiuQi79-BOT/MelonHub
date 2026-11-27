package com.melonhub.rbac.api.context.vo;

/**
 * 权限上下文中已激活生效的角色简要 VO。
 */
public record ContextRoleVO(long id, String name, String code) {}
