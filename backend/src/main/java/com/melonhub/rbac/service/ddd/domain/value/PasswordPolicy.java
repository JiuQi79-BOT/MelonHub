package com.melonhub.rbac.service.ddd.domain.value;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 凭据与密码领域策略：封装管理员密码与统一身份密码的长度和 BCrypt 72 字节上限规则。
 */
public final class PasswordPolicy {
    public static final int BCRYPT_MAX_BYTES = 72;
    public static final int ADMIN_MIN_LENGTH = 12;
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9_@-][A-Za-z0-9._@-]{2,99}");

    private PasswordPolicy() {}

    public static void validateBootstrapAdmin(String username, String password) {
        if (password == null || password.length() < ADMIN_MIN_LENGTH) {
            throw new IllegalStateException("ADMIN_PASSWORD must contain at least 12 characters");
        }
        if (utf8Bytes(password) > BCRYPT_MAX_BYTES) {
            throw new IllegalStateException("ADMIN_PASSWORD must not exceed 72 bytes in UTF-8");
        }
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            throw new IllegalStateException("ADMIN_USERNAME must be 3-100 letters, digits or _.@-");
        }
    }

    public static void validateAdminPasswordBytes(String password) {
        if (password == null || utf8Bytes(password) > BCRYPT_MAX_BYTES) {
            throw DomainException.bad("密码不能超过 72 字节（UTF-8 编码，约 24 个汉字）");
        }
    }

    public static void validateIdentityPassword(String password) {
        if (password == null || password.isBlank() || utf8Bytes(password) > BCRYPT_MAX_BYTES) {
            throw DomainException.bad("统一身份密码不能为空，且 UTF-8 长度不能超过 72 字节");
        }
    }

    public static boolean exceedsBcryptLimit(String password) {
        return password == null || utf8Bytes(password) > BCRYPT_MAX_BYTES;
    }

    public static int utf8Bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
