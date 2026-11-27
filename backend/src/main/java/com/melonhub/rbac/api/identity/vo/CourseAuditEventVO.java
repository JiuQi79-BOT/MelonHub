package com.melonhub.rbac.api.identity.vo;

/**
 * 课程系统审计日志单条响应 VO。
 */
public record CourseAuditEventVO(long id, String at, CourseAuditActorVO actor, String type, String target) {}
