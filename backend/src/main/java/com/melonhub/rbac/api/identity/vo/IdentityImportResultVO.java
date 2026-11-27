package com.melonhub.rbac.api.identity.vo;

import java.util.List;

/**
 * 统一身份凭据批量导入汇总响应 VO。
 */
public record IdentityImportResultVO(int total, List<IdentityImportRowVO> items) {}
