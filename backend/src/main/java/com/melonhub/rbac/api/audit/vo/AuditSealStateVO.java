package com.melonhub.rbac.api.audit.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.melonhub.rbac.api.shared.json.ApiTimestampSerializer;
import java.time.Instant;

/**
 * 审计哈希链封存游标状态 VO。
 */
public record AuditSealStateVO(
        long lastId,
        long lastSeq,
        String lastHash,
        long sealedCount,
        long anchorSeq,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant updatedAt,
        long unsealed,
        long total
) {}
