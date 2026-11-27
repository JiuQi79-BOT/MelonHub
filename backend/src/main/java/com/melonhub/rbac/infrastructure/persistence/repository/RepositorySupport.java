package com.melonhub.rbac.infrastructure.persistence.repository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * 纯 Spring Data JPA 仓储共用的无状态辅助方法（模糊匹配转义、分页换算、整秒时钟与截断）。
 */
final class RepositorySupport {
    /** 用于 {@code (:filter = false or col in :ids)} 分支关闭时的非空占位集合，避免空 IN 参数。 */
    static final List<Long> NO_IDS = List.of(-1L);

    private RepositorySupport() {}

    /** 构造用于 {@code escape '!'} 的 LIKE 模糊匹配模式。 */
    static String like(String keyword) {
        return "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    static String likeOrNull(String keyword) {
        return keyword == null || keyword.isBlank() ? null : like(keyword.strip());
    }

    static String textOrNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    static Pageable page(int page, int size) {
        return PageRequest.of(Math.max(1, page) - 1, Math.max(1, size));
    }

    /** MySQL TIMESTAMP 按整秒存储，写入前截断至秒以保证内存与库中时间一致。 */
    static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
