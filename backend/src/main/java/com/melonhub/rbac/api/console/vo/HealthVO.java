package com.melonhub.rbac.api.console.vo;

/**
 * 平台健康探针响应 VO。
 */
public record HealthVO(String status, String database, String cache, String model) {}
