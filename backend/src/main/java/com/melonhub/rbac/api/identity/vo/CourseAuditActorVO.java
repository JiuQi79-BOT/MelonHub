package com.melonhub.rbac.api.identity.vo;

import java.util.List;

/**
 * 课程审计事件操作人展示 VO。
 */
public record CourseAuditActorVO(Long id, String name, List<String> roles, boolean deleted) {}
