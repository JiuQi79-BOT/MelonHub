package com.melonhub.rbac.api.domainrbac.vo;

/**
 * 角色成员列表项响应 VO。
 */
public record RoleMemberVO(long id, String name, String externalId, String email, boolean enabled) {}
