package com.melonhub.rbac.service.ddd.domain.model;

import com.melonhub.rbac.service.ddd.domain.value.AdminScope;
import com.melonhub.rbac.service.ddd.domain.value.DomainException;
import com.melonhub.rbac.service.ddd.domain.value.PasswordPolicy;

/**
 * 管理员凭据聚合根：封装控制台登录状态机（锁定、密码、停用、TOTP 动态口令防重放）、改密与两步验证规则。
 */
public final class AdminCredentialAccount {
    public enum LoginCheck {
        LOCKED,
        PASSWORD_MISMATCH,
        DISABLED,
        MFA_CODE_MISSING,
        MFA_INVALID,
        READY_NO_MFA,
        READY_CONSUME_MFA
    }

    private final long id;
    private final String username;
    private final String displayName;
    private final String role;
    private final boolean enabled;
    private final String passwordHash;
    private final boolean locked;
    private final String mfaSecret;
    private final long mfaLastStep;
    private final long credentialVersion;
    private final String kind;
    private final boolean root;
    private final AdminScope scope;

    public AdminCredentialAccount(long id, String username, String displayName, String role, boolean enabled,
                                  String passwordHash, boolean locked, String mfaSecret, long mfaLastStep,
                                  long credentialVersion, String kind, boolean root, AdminScope scope) {
        this.id = id;
        this.username = username;
        this.displayName = displayName;
        this.role = role;
        this.enabled = enabled;
        this.passwordHash = passwordHash;
        this.locked = locked;
        this.mfaSecret = mfaSecret;
        this.mfaLastStep = mfaLastStep;
        this.credentialVersion = credentialVersion;
        this.kind = kind;
        this.root = root;
        this.scope = scope;
    }

    public long id() { return id; }
    public String username() { return username; }
    public String displayName() { return displayName; }
    public String role() { return role; }
    public boolean enabled() { return enabled; }
    public String passwordHash() { return passwordHash; }
    public boolean locked() { return locked; }
    public String mfaSecret() { return mfaSecret; }
    public long credentialVersion() { return credentialVersion; }
    public String kind() { return kind; }
    public boolean root() { return root; }
    public AdminScope scope() { return scope; }
    public boolean mfaEnabled() { return mfaSecret != null; }

    public LoginCheck evaluateLogin(boolean passwordMatches, String mfaCode, Long verifiedStep) {
        if (locked) return LoginCheck.LOCKED;
        if (!passwordMatches) return LoginCheck.PASSWORD_MISMATCH;
        if (!enabled) return LoginCheck.DISABLED;
        if (mfaSecret == null) return LoginCheck.READY_NO_MFA;
        if (mfaCode == null || mfaCode.isBlank()) return LoginCheck.MFA_CODE_MISSING;
        if (verifiedStep == null || verifiedStep <= mfaLastStep) return LoginCheck.MFA_INVALID;
        return LoginCheck.READY_CONSUME_MFA;
    }

    public void validatePasswordChange(boolean currentPasswordMatches, String currentPassword, String newPassword) {
        if (!currentPasswordMatches) {
            throw DomainException.bad("BAD_CREDENTIALS", "当前密码不正确");
        }
        if (currentPassword.equals(newPassword)) {
            throw DomainException.bad("新密码不能与当前密码相同");
        }
        PasswordPolicy.validateAdminPasswordBytes(newPassword);
    }

    public void assertCanSetupMfa() {
        if (mfaEnabled()) {
            throw DomainException.conflict("已启用两步验证，请先停用再重新绑定");
        }
    }

    public String totpUri(String pendingSecret) {
        String label = "MelonHub:" + username;
        return "otpauth://totp/" + label + "?secret=" + pendingSecret + "&issuer=MelonHub&digits=6&period=30";
    }

    public void assertCanConfirmMfa(String pendingSecret, Long verifiedStep) {
        if (pendingSecret == null) {
            throw DomainException.bad("请先生成绑定密钥");
        }
        if (verifiedStep == null) {
            throw DomainException.bad("MFA_INVALID", "动态口令不正确，请确认设备时间准确");
        }
    }

    public void assertCanDisableMfa(boolean passwordMatches, Long verifiedStep) {
        if (!mfaEnabled()) {
            throw DomainException.bad("未启用两步验证");
        }
        if (!passwordMatches) {
            throw DomainException.bad("BAD_CREDENTIALS", "当前密码不正确");
        }
        if (verifiedStep == null || verifiedStep <= mfaLastStep) {
            throw DomainException.bad("MFA_INVALID", "动态口令不正确或已使用");
        }
    }
}
