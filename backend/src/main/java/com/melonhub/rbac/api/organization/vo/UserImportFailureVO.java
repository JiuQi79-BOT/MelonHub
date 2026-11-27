package com.melonhub.rbac.api.organization.vo;

/**
 * 批量导入用户失败行明细 VO。
 */
public record UserImportFailureVO(int line, String externalId, String error) {}
