package com.melonhub.rbac.api.shared.vo;

import java.util.List;

/**
 * 控制台通用分页响应 VO。
 */
public record PageVO<T>(List<T> items, long total, int page, int size) {}
