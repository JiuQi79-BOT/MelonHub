package com.melonhub.rbac.api.domainrbac.vo;

/**
 * 跨部门复制角色与权限结果 VO。
 */
public record DomainCopyResultVO(int permissionsCreated, int permissionsSkipped, int rolesCreated, int rolesSkipped) {}
