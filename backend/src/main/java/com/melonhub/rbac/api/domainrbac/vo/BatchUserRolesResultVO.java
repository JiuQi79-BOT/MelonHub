package com.melonhub.rbac.api.domainrbac.vo;

/**
 * 批量追加角色授权结果 VO。
 */
public record BatchUserRolesResultVO(int users, int changedUsers, int grantsAdded) {}
