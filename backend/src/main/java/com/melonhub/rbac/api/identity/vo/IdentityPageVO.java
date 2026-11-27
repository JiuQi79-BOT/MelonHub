package com.melonhub.rbac.api.identity.vo;

import java.util.List;

/**
 * 课程系统统一身份分页响应 VO。
 */
public record IdentityPageVO<T>(int page, int pageSize, long total, List<T> items) {}
