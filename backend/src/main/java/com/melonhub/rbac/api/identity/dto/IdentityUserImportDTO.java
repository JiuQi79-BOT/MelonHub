package com.melonhub.rbac.api.identity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 统一身份用户凭据批量导入请求 DTO。
 */
public record IdentityUserImportDTO(@NotNull @Size(min = 1, max = 500) List<@Valid IdentityUserRowDTO> users) {}
