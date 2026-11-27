package com.melonhub.rbac.service.ddd.domain.value;

/**
 * 纯领域异常：不依赖 Spring/HTTP，通过语义类别 {@link Kind} 表达业务规则违背，
 * 由应用层或全局异常映射器转换为统一 API 错误响应。
 */
public final class DomainException extends RuntimeException {
    public enum Kind {
        BAD_REQUEST,
        UNAUTHORIZED,
        FORBIDDEN,
        NOT_FOUND,
        CONFLICT,
        BUSY
    }

    private final Kind kind;
    private final String code;

    public DomainException(Kind kind, String code, String message) {
        super(message);
        this.kind = kind;
        this.code = code;
    }

    public Kind kind() { return kind; }

    public String code() { return code; }

    public static DomainException bad(String message) {
        return new DomainException(Kind.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    public static DomainException bad(String code, String message) {
        return new DomainException(Kind.BAD_REQUEST, code, message);
    }

    public static DomainException unauthorized() {
        return new DomainException(Kind.UNAUTHORIZED, "UNAUTHORIZED", "请登录或检查接口凭据");
    }

    public static DomainException unauthorized(String code, String message) {
        return new DomainException(Kind.UNAUTHORIZED, code, message);
    }

    public static DomainException forbidden(String message) {
        return new DomainException(Kind.FORBIDDEN, "FORBIDDEN", message);
    }

    public static DomainException forbidden(String code, String message) {
        return new DomainException(Kind.FORBIDDEN, code, message);
    }

    public static DomainException missing(String message) {
        return new DomainException(Kind.NOT_FOUND, "NOT_FOUND", message);
    }

    public static DomainException conflict(String message) {
        return new DomainException(Kind.CONFLICT, "CONFLICT", message);
    }

    public static DomainException conflict(String code, String message) {
        return new DomainException(Kind.CONFLICT, code, message);
    }

    public static DomainException busy(String code, String message) {
        return new DomainException(Kind.BUSY, code, message);
    }
}
