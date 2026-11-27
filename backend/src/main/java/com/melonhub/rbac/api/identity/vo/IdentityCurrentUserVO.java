package com.melonhub.rbac.api.identity.vo;

import java.util.List;

/**
 * 统一身份当前用户及角色权限响应 VO。
 */
public record IdentityCurrentUserVO(String subject, List<String> roles, List<String> permissions, String name, String no) {}
