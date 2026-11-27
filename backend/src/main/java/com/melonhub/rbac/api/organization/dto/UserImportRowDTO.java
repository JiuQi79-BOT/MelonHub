package com.melonhub.rbac.api.organization.dto;

import java.util.List;

/**
 * 批量导入用户单行 DTO（导入服务逐行校验并收集失败明细，避免单行错误整批拒绝）。
 */
public record UserImportRowDTO(String name, String externalId, String email, List<String> departmentCodes) {}
