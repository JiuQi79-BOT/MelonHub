package com.melonhub.rbac.api.organization.vo;

import java.util.List;

/**
 * 批量导入用户汇总结果 VO。
 */
public record UserImportResultVO(int created, int updated, List<UserImportFailureVO> failed) {}
