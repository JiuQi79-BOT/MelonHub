package com.melonhub.rbac.service.port.admin;

import com.melonhub.rbac.service.port.admin.AdminModels.AdminAccountResult;
import com.melonhub.rbac.service.port.admin.AdminModels.CreateAccountCommand;
import com.melonhub.rbac.service.port.admin.AdminModels.LoginCommand;
import com.melonhub.rbac.service.port.admin.AdminModels.LoginResult;
import com.melonhub.rbac.service.port.admin.AdminModels.MfaDisableCommand;
import com.melonhub.rbac.service.port.admin.AdminModels.MfaSetupResult;
import com.melonhub.rbac.service.port.admin.AdminModels.PasswordChangeCommand;
import com.melonhub.rbac.service.port.admin.AdminModels.PasswordResetCommand;
import com.melonhub.rbac.service.port.admin.AdminModels.PrincipalResult;
import com.melonhub.rbac.service.port.admin.AdminModels.UpdateAccountCommand;
import com.melonhub.rbac.service.port.security.AdminFunction;
import com.melonhub.rbac.service.port.security.AdminPrincipal;
import com.melonhub.rbac.service.port.security.ApplicationCaller;
import java.util.List;
import java.util.Set;

/**
 * 控制台管理员认证、会话、MFA、API Key 校验与管理员层级账号管理用例端口。
 */
public interface AuthUseCase {
    int PASSWORD_MAX_BYTES = 72;

    static PrincipalResult principalResult(AdminPrincipal principal, boolean mfaEnabled) {
        return new PrincipalResult(
                principal.id(),
                principal.username(),
                principal.displayName(),
                principal.role(),
                principal.roleName(),
                principal.functions().stream().map(AdminFunction::code).sorted().toList(),
                principal.root(),
                principal.kind(),
                principal.allDepartments(),
                principal.departmentIds().stream().sorted().toList(),
                principal.allApplications(),
                principal.applicationIds().stream().sorted().toList(),
                mfaEnabled);
    }

    static LoginResult loginResult(String token, int expiresIn, int idleTimeout,
                                   AdminPrincipal principal, boolean mfaEnabled) {
        return new LoginResult(
                token,
                expiresIn,
                idleTimeout,
                principal.id(),
                principal.username(),
                principal.displayName(),
                principal.role(),
                principal.roleName(),
                principal.functions().stream().map(AdminFunction::code).sorted().toList(),
                principal.root(),
                principal.kind(),
                principal.allDepartments(),
                principal.departmentIds().stream().sorted().toList(),
                principal.allApplications(),
                principal.applicationIds().stream().sorted().toList(),
                mfaEnabled);
    }

    LoginResult login(LoginCommand input);

    AdminPrincipal admin(String authorization);

    String sessionHash(String authorization);

    void logout(AdminPrincipal principal, String authorization);

    void changePassword(AdminPrincipal principal, String authorization, PasswordChangeCommand input);

    MfaSetupResult setupMfa(AdminPrincipal principal);

    void confirmMfa(AdminPrincipal principal, String code);

    void disableMfa(AdminPrincipal principal, MfaDisableCommand input);

    void resetMfa(long id, AdminPrincipal actor);

    PrincipalResult me(AdminPrincipal principal);

    ApplicationCaller application(String key);

    void flushKeyUsage();

    int purgeSessions();

    AdminPrincipal principal(String username);

    List<AdminAccountResult> accounts(AdminPrincipal actor);

    long createAccount(CreateAccountCommand input, AdminPrincipal actor);

    void updateAccount(long id, UpdateAccountCommand input, AdminPrincipal actor);

    void resetPassword(long id, PasswordResetCommand input, AdminPrincipal actor);

    void unlock(long id, AdminPrincipal actor);

    void deleteAccount(long id, AdminPrincipal actor);

    void lockTree();

    void requireConsistentWithRole(String role, Set<String> functions);
}
