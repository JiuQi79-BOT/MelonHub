package com.melonhub.rbac.api.identity.vo;

/**
 * 统一身份凭据批量导入单条成功明细 VO。
 */
public record IdentityImportRowVO(String username, long userId, String status) {}
