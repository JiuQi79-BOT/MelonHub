package com.melonhub.rbac.service.port.common;

import java.time.ZoneId;
import java.util.List;

/**
 * 应用层通用分页结果模型，与 HTTP 分页视图解耦。
 */
public record PageResult<T>(List<T> items, long total, int page, int size) {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public static <T> PageResult<T> of(List<T> items, long total, int page, int size) {
        return new PageResult<>(items, total, page, size);
    }
}
