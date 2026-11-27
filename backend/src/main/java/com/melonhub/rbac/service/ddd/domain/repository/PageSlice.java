package com.melonhub.rbac.service.ddd.domain.repository;

import java.util.List;

/** 纯领域强类型分页切片值对象。 */
public record PageSlice<T>(List<T> items, long total) {}
