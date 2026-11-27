package com.melonhub.rbac.service.ddd.application.shared;

import com.melonhub.rbac.service.ddd.domain.value.DomainException;
import com.melonhub.rbac.service.ddd.domain.value.DomainVisibility;
import com.melonhub.rbac.service.port.error.ApiException;
import com.melonhub.rbac.service.port.security.Visibility;
import java.util.function.Supplier;

/**
 * DDD 应用层边界辅助：将纯领域异常 {@link DomainException} 统一翻译为 {@link ApiException}，
 * 并将用例端口的 {@link Visibility} 映射为领域值对象 {@link DomainVisibility}。
 */
public final class DddBoundary {
    private DddBoundary() {}

    public static <T> T execute(Supplier<T> action) {
        try {
            return action.get();
        } catch (DomainException e) {
            throw toApiException(e);
        }
    }

    public static void run(Runnable action) {
        try {
            action.run();
        } catch (DomainException e) {
            throw toApiException(e);
        }
    }

    public static ApiException toApiException(DomainException e) {
        return switch (e.kind()) {
            case BAD_REQUEST -> ApiException.bad(e.code(), e.getMessage());
            case UNAUTHORIZED -> ApiException.unauthorized(e.code(), e.getMessage());
            case FORBIDDEN -> ApiException.forbidden(e.code(), e.getMessage());
            case NOT_FOUND -> ApiException.missing(e.code(), e.getMessage());
            case CONFLICT -> ApiException.conflict(e.code(), e.getMessage());
            case BUSY -> ApiException.busy(e.code(), e.getMessage());
        };
    }

    public static DomainVisibility toDomainVisibility(Visibility visibility) {
        return visibility == null
                ? DomainVisibility.ALL
                : new DomainVisibility(visibility.applications(), visibility.departments());
    }
}
