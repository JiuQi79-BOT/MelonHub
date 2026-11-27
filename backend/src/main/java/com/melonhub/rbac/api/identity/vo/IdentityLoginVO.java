package com.melonhub.rbac.api.identity.vo;

/**
 * 统一身份登录成功响应 VO。
 */
public record IdentityLoginVO(String accessToken, String tokenType, long expiresIn) {}
