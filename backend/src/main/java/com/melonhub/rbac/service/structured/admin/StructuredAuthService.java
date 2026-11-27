package com.melonhub.rbac.service.structured.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.melonhub.rbac.infrastructure.cache.PermissionCache;
import com.melonhub.rbac.infrastructure.persistence.entity.AdminAccount;
import com.melonhub.rbac.infrastructure.persistence.entity.AdminRole;
import com.melonhub.rbac.infrastructure.persistence.repository.AdminAccountRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AdminRoleRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AdminSessionRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.ApplicationKeyRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.ApplicationRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.DepartmentRepository;
import com.melonhub.rbac.infrastructure.security.ClientAddress;
import com.melonhub.rbac.infrastructure.security.Credentials;
import com.melonhub.rbac.infrastructure.security.Totp;
import com.melonhub.rbac.service.port.admin.AdminModels;
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
import com.melonhub.rbac.service.port.admin.AuthUseCase;
import com.melonhub.rbac.service.port.audit.AuditUseCase;
import com.melonhub.rbac.service.port.error.ApiException;
import com.melonhub.rbac.service.port.security.AdminFunction;
import com.melonhub.rbac.service.port.security.AdminPrincipal;
import com.melonhub.rbac.service.port.security.ApplicationCaller;
import com.melonhub.rbac.service.structured.admin.StructuredAdminHierarchyProcedures.AccountNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Structured 管理员认证、会话、MFA、API Key 校验与管理员层级账号树维护服务。
 */
@Service
@ConditionalOnProperty(name = "melonhub.backend-strategy", havingValue = "structured", matchIfMissing = true)
public class StructuredAuthService implements AuthUseCase, ApplicationRunner {
    private record KeyMeta(String a, long k) {}
    private record KeyState(boolean valid, Long exp) {}

    private static final Logger log = LoggerFactory.getLogger(StructuredAuthService.class);
    private final AdminAccountRepository accounts;
    private final AdminSessionRepository sessions;
    private final AdminRoleRepository roles;
    private final ApplicationRepository applications;
    private final ApplicationKeyRepository keys;
    private final DepartmentRepository departments;
    private final AuditUseCase audit;
    private final PermissionCache cache;
    private final ObjectMapper json;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private final String bootstrapUser;
    private final String bootstrapPassword;
    private final boolean bootstrapReset;
    private final int maxFailures;
    private final int lockMinutes;
    private final int sessionSeconds;
    private final int idleMinutes;
    private final String dummyHash;
    private final Set<Long> usedKeys = ConcurrentHashMap.newKeySet();

    public StructuredAuthService(AdminAccountRepository accounts, AdminSessionRepository sessions,
                                 AdminRoleRepository roles, ApplicationRepository applications,
                                 ApplicationKeyRepository keys, DepartmentRepository departments,
                                 AuditUseCase audit, PermissionCache cache, ObjectMapper json,
                                 @Value("${melonhub.admin-username}") String bootstrapUser,
                                 @Value("${melonhub.admin-password}") String bootstrapPassword,
                                 @Value("${melonhub.admin-password-reset:false}") boolean bootstrapReset,
                                 @Value("${melonhub.admin-security.max-failures:5}") int maxFailures,
                                 @Value("${melonhub.admin-security.lock-minutes:15}") int lockMinutes,
                                 @Value("${melonhub.admin-security.session-hours:8}") int sessionHours,
                                 @Value("${melonhub.admin-security.idle-minutes:30}") int idleMinutes) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.roles = roles;
        this.applications = applications;
        this.keys = keys;
        this.departments = departments;
        this.audit = audit;
        this.cache = cache;
        this.json = json;
        this.bootstrapUser = bootstrapUser;
        this.bootstrapPassword = bootstrapPassword;
        this.bootstrapReset = bootstrapReset;
        this.maxFailures = maxFailures;
        this.lockMinutes = lockMinutes;
        this.sessionSeconds = sessionHours * 3600;
        this.idleMinutes = idleMinutes;
        this.dummyHash = encoder.encode(Credentials.token());
    }

    @Override
    public void run(ApplicationArguments args) {
        if (bootstrapPassword == null || bootstrapPassword.length() < 12) {
            throw new IllegalStateException("ADMIN_PASSWORD must contain at least 12 characters");
        }
        if (bytes(bootstrapPassword) > PASSWORD_MAX_BYTES) {
            throw new IllegalStateException("ADMIN_PASSWORD must not exceed 72 bytes in UTF-8");
        }
        if (!bootstrapUser.matches(AdminModels.USERNAME_REGEX)) {
            throw new IllegalStateException("ADMIN_USERNAME must be 3-100 letters, digits or _.@-");
        }
        if (!accounts.existsByUsername(bootstrapUser)) {
            accounts.createAccount(bootstrapUser, "平台管理员", AdminPrincipal.SUPER_ADMIN, encode(bootstrapPassword),
                    true, null, AdminPrincipal.MANAGER, true, true);
            log.info("Bootstrap super administrator account created");
        } else if (bootstrapReset) {
            accounts.resetBootstrap(bootstrapUser, encode(bootstrapPassword), AdminPrincipal.SUPER_ADMIN);
            accounts.credentials(bootstrapUser).ifPresent(account -> endSessions(account.getId()));
            log.warn("Bootstrap administrator restored from ADMIN_PASSWORD; set ADMIN_PASSWORD_RESET=false again");
        }
        sessions.purgeExpired(idleMinutes, 100_000);
    }

    @Override
    public LoginResult login(LoginCommand input) {
        String username = input.username();
        Optional<AdminAccountRepository.Credentials> found = accounts.credentials(username);
        if (found.isEmpty()) {
            matches(input.password(), dummyHash);
            audit.audit(username, "LOGIN_FAILED", username, null, null, "账号不存在");
            throw badCredentials();
        }
        AdminAccountRepository.Credentials account = found.get();
        boolean isLocked = account.getLockedUntil() != null && account.getLockedUntil().isAfter(Instant.now());
        if (isLocked) {
            audit.audit(username, "LOGIN_BLOCKED", username, null, null, "账号处于锁定期");
            throw locked();
        }
        if (!matches(input.password(), account.getPasswordHash())) {
            boolean nowLocked = accounts.recordFailure(account.getId(), maxFailures, lockMinutes);
            audit.audit(username, nowLocked ? "ACCOUNT_LOCKED" : "LOGIN_FAILED", username, null, null,
                    nowLocked ? "连续 " + maxFailures + " 次密码错误，锁定 " + lockMinutes + " 分钟" : "密码错误");
            throw nowLocked ? locked() : badCredentials();
        }
        if (!account.isEnabled()) {
            audit.audit(username, "LOGIN_FAILED", username, null, null, "账号已停用");
            throw ApiException.forbidden("ACCOUNT_DISABLED", "账号已停用，请联系超级管理员");
        }
        if (account.getMfaSecret() != null) {
            String code = input.mfaCode();
            if (code == null || code.isBlank()) {
                throw ApiException.unauthorized("MFA_REQUIRED", "该账号已启用两步验证，请输入认证器动态口令");
            }
            Long step = Totp.verify(account.getMfaSecret(), code.strip());
            if (step == null || step <= account.getMfaLastStep()
                    || accounts.consumeMfaStep(account.getId(), account.getMfaSecret(), step) == 0) {
                boolean nowLocked = accounts.recordFailure(account.getId(), maxFailures, lockMinutes);
                audit.audit(username, nowLocked ? "ACCOUNT_LOCKED" : "LOGIN_FAILED", username, null, null,
                        nowLocked ? "连续失败锁定" : "动态口令不正确或已使用");
                throw ApiException.unauthorized("MFA_INVALID", "动态口令不正确或已使用");
            }
        }
        String token = Credentials.token();
        if (accounts.createSessionIfCredentialsMatch(Credentials.hash(token), account.getId(), account.getCredentialVersion(),
                account.getPasswordHash(), account.getMfaSecret(), nowSec().plusSeconds(sessionSeconds), ClientAddress.current()) == 0) {
            audit.audit(username, "LOGIN_FAILED", username, null, null, "登录过程中账号凭据已变更");
            throw ApiException.unauthorized("BAD_CREDENTIALS", "账号凭据已变更，请重新登录");
        }
        accounts.recordSuccess(account.getId(), nowSec());
        audit.audit(username, "LOGIN", username, null, null, "");
        AdminPrincipal principal = principal(account.getId(), account.getUsername(), account.getDisplayName(), account.getRole(),
                account.getKind(), account.isRoot(), account.isAllDepartments(), account.isAllApplications());
        return AuthUseCase.loginResult(token, sessionSeconds, idleMinutes * 60, principal, account.getMfaSecret() != null);
    }

    @Override
    public AdminPrincipal admin(String authorization) {
        String hash = sessionHash(authorization);
        Instant now = Instant.now();
        AdminSessionRepository.ActiveSession session = sessions.active(hash, now, now.minus(idleMinutes, ChronoUnit.MINUTES))
                .orElseThrow(ApiException::unauthorized);
        if (session.getLastSeenAt().isBefore(now.minusSeconds(60))) {
            sessions.touch(hash, nowSec());
        }
        return principal(session.getId(), session.getUsername(), session.getDisplayName(), session.getRole(),
                session.getKind(), session.isRoot(), session.isAllDepartments(), session.isAllApplications());
    }

    @Override
    public String sessionHash(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw ApiException.unauthorized();
        }
        String hash = Credentials.hashOrNull(authorization.substring(7));
        if (hash == null) {
            throw ApiException.unauthorized();
        }
        return hash;
    }

    @Override
    public void logout(AdminPrincipal principal, String authorization) {
        sessions.deleteByHashes(List.of(sessionHash(authorization)));
        audit.audit(principal.actor(), "LOGOUT", principal.username(), null, null, "");
    }

    @Override
    @Transactional
    public void changePassword(AdminPrincipal principal, String authorization, PasswordChangeCommand input) {
        AdminAccountRepository.Credentials account = accounts.credentials(principal.id())
                .orElseThrow(ApiException::unauthorized);
        if (!matches(input.currentPassword(), account.getPasswordHash())) {
            throw ApiException.bad("BAD_CREDENTIALS", "当前密码不正确");
        }
        if (input.currentPassword().equals(input.newPassword())) {
            throw ApiException.bad("新密码不能与当前密码相同");
        }
        if (accounts.replacePassword(principal.id(), account.getPasswordHash(), encode(input.newPassword()), nowSec()) == 0) {
            throw ApiException.bad("BAD_CREDENTIALS", "密码已被更改，请重新登录后再试");
        }
        String keepHash = sessionHash(authorization);
        accounts.credentials(principal.id()).ifPresent(updated -> sessions.moveToVersion(keepHash, principal.id(), updated.getCredentialVersion()));
        sessions.deleteOthers(principal.id(), keepHash);
        audit.audit(principal.actor(), "PASSWORD_CHANGED", principal.username(), null, null, "其他会话已下线");
    }

    @Override
    public MfaSetupResult setupMfa(AdminPrincipal principal) {
        AdminAccountRepository.Credentials account = accounts.credentials(principal.id())
                .orElseThrow(ApiException::unauthorized);
        if (account.getMfaSecret() != null) {
            throw ApiException.conflict("已启用两步验证，请先停用再重新绑定");
        }
        String secret = Totp.newSecret();
        accounts.setMfaPending(principal.id(), secret);
        String label = "MelonHub:" + principal.username();
        return new MfaSetupResult(secret, "otpauth://totp/" + label + "?secret=" + secret + "&issuer=MelonHub&digits=6&period=30");
    }

    @Override
    @Transactional
    public void confirmMfa(AdminPrincipal principal, String code) {
        accounts.credentials(principal.id()).orElseThrow(ApiException::unauthorized);
        String pending = accounts.pendingMfaSecret(principal.id()).stream().filter(Objects::nonNull).findFirst().orElse(null);
        if (pending == null) {
            throw ApiException.bad("请先生成绑定密钥");
        }
        Long step = Totp.verify(pending, code);
        if (step == null) {
            throw ApiException.bad("MFA_INVALID", "动态口令不正确，请确认设备时间准确");
        }
        if (accounts.confirmMfa(principal.id(), pending, step) == 0) {
            throw ApiException.conflict("绑定密钥已更换，请使用最新的二维码重新绑定");
        }
        endSessions(principal.id());
        audit.audit(principal.actor(), "MFA_ENABLED", principal.username(), null, null, "");
    }

    @Override
    @Transactional
    public void disableMfa(AdminPrincipal principal, MfaDisableCommand input) {
        AdminAccountRepository.Credentials account = accounts.credentials(principal.id())
                .orElseThrow(ApiException::unauthorized);
        if (account.getMfaSecret() == null) {
            throw ApiException.bad("未启用两步验证");
        }
        if (!matches(input.password(), account.getPasswordHash())) {
            throw ApiException.bad("BAD_CREDENTIALS", "当前密码不正确");
        }
        Long step = Totp.verify(account.getMfaSecret(), input.code());
        if (step == null || step <= account.getMfaLastStep()
                || accounts.disableMfaWithStep(principal.id(), account.getMfaSecret(), step) == 0) {
            throw ApiException.bad("MFA_INVALID", "动态口令不正确或已使用");
        }
        endSessions(principal.id());
        audit.audit(principal.actor(), "MFA_DISABLED", principal.username(), null, null, "");
    }

    @Override
    @Transactional
    public void resetMfa(long id, AdminPrincipal actor) {
        accounts.lockAll();
        String username = requireManagedAccount(loadNodes(), actor, id).username();
        if (accounts.disableMfa(id) == 0) {
            throw ApiException.missing("管理员账号不存在");
        }
        endSessions(id);
        audit.audit(actor.actor(), "MFA_RESET", username, null, null, "两步验证已重置");
    }

    @Override
    public PrincipalResult me(AdminPrincipal principal) {
        boolean mfaEnabled = accounts.credentials(principal.id()).map(account -> account.getMfaSecret() != null).orElse(false);
        return AuthUseCase.principalResult(principal, mfaEnabled);
    }

    @Override
    public ApplicationCaller application(String key) {
        String hash = Credentials.hashOrNull(key);
        if (hash == null) {
            throw ApiException.unauthorized();
        }
        PermissionCache.Lookup lookup = cache.applicationKey(hash);
        if (lookup.status() == PermissionCache.Status.HIT) {
            KeyMeta meta = read(lookup.meta(), KeyMeta.class);
            KeyState state = read(lookup.payload(), KeyState.class);
            if (meta == null || state == null) {
                throw ApiException.unauthorized();
            }
            if (!state.valid() || (state.exp() != null && state.exp() <= Instant.now().getEpochSecond())) {
                throw ApiException.unauthorized();
            }
            return used(Long.parseLong(meta.a()), meta.k());
        }
        Instant now = Instant.now();
        ApplicationKeyRepository.KeyState row = keys.byHash(hash).orElseThrow(ApiException::unauthorized);
        boolean valid = row.isApplicationEnabled() && row.getRevokedAt() == null
                && (row.getExpiresAt() == null || row.getExpiresAt().isAfter(now));
        if (lookup.status() == PermissionCache.Status.NONE) {
            cache.putApplicationKey(hash, write(new KeyMeta(String.valueOf(row.getApplicationId()), row.getKeyId())));
        }
        if (lookup.status() == PermissionCache.Status.MISS) {
            Long exp = row.getExpiresAt() == null ? null : row.getExpiresAt().getEpochSecond();
            cache.store(lookup.storeKey(), write(new KeyState(valid, exp)), PermissionCache.ENTRY_TTL_SECONDS);
        }
        if (!valid) {
            throw ApiException.unauthorized();
        }
        return used(row.getApplicationId(), row.getKeyId());
    }

    @Override
    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void flushKeyUsage() {
        if (usedKeys.isEmpty()) {
            return;
        }
        List<Long> ids = new ArrayList<>(usedKeys);
        if (ids.isEmpty()) {
            return;
        }
        usedKeys.removeAll(ids);
        try {
            keys.markUsed(ids, nowSec());
        } catch (DataAccessException e) {
            log.warn("Could not record API key usage: {}", e.getClass().getSimpleName());
        }
    }

    @Override
    public int purgeSessions() {
        return sessions.purgeExpired(idleMinutes, 5000);
    }

    @Override
    public AdminPrincipal principal(String username) {
        AdminAccountRepository.Credentials account = accounts.credentials(username)
                .orElseThrow(() -> ApiException.missing("管理员账号不存在"));
        return principal(account.getId(), account.getUsername(), account.getDisplayName(), account.getRole(), account.getKind(),
                account.isRoot(), account.isAllDepartments(), account.isAllApplications());
    }

    @Override
    public List<AdminAccountResult> accounts(AdminPrincipal actor) {
        Instant now = Instant.now();
        Set<Long> manageableIds = StructuredAdminHierarchyProcedures.manageableAccountIds(loadNodes(), actor.id());
        Map<Long, List<Long>> appsByAccount = new HashMap<>();
        for (AdminAccountRepository.AccountApplication pair : accounts.applicationPairs()) {
            appsByAccount.computeIfAbsent(pair.getAccountId(), k -> new ArrayList<>()).add(pair.getApplicationId());
        }
        Map<Long, List<Long>> deptsByAccount = new HashMap<>();
        for (AdminAccountRepository.AccountDepartment pair : accounts.departmentPairs()) {
            deptsByAccount.computeIfAbsent(pair.getAccountId(), k -> new ArrayList<>()).add(pair.getDepartmentId());
        }
        Map<Long, Long> sessionCounts = new HashMap<>();
        for (AdminSessionRepository.SessionCount c : sessions.activeCounts(now)) {
            sessionCounts.put(c.getAccountId(), c.getSessions());
        }
        List<AdminAccountResult> result = new ArrayList<>();
        for (AdminAccount a : accounts.listed()) {
            long id = a.getId();
            boolean manageable = manageableIds.contains(id);
            if (!manageable && id != actor.id()) {
                continue;
            }
            result.add(new AdminAccountResult(
                    id, a.getUsername(), a.getDisplayName(), a.getRole().getCode(), a.isEnabled(), a.getFailedAttempts(),
                    a.getLockedUntil() != null && a.getLockedUntil().isAfter(now) ? 1L : 0L,
                    a.getLockedUntil(), a.getLastLoginAt(), a.getPasswordChangedAt(), a.getCreatedAt(),
                    sessionCounts.getOrDefault(id, 0L),
                    a.getMfaSecret() != null ? 1L : 0L,
                    a.isAllApplications(),
                    a.getParent() == null ? null : a.getParent().getId(),
                    a.getParent() == null ? null : a.getParent().getUsername(),
                    a.getKind(), a.isAllDepartments(),
                    appsByAccount.getOrDefault(id, List.of()),
                    deptsByAccount.getOrDefault(id, List.of()),
                    manageable));
        }
        return result;
    }

    @Override
    @Transactional
    public long createAccount(CreateAccountCommand input, AdminPrincipal actor) {
        accounts.lockAll();
        requireRole(input.role());
        List<AccountNode> nodes = loadNodes();
        Map<String, Set<String>> roleFunctions = functionsByRole();
        long parentId = requestedParent(input.parentId(), input.parentUsername(), actor.id());
        AccountNode parent = StructuredAdminHierarchyProcedures.findNode(nodes, parentId);
        if (parent == null) {
            throw ApiException.bad("上级管理员不存在");
        }
        if (parentId != actor.id() && !StructuredAdminHierarchyProcedures.manages(nodes, actor.id(), parentId)) {
            throw ApiException.forbidden("只能在自己或自己管理的管理员下创建账号");
        }
        String kind = input.kind() == null ? AdminPrincipal.MANAGER : input.kind();
        boolean allDepartments = everything(input.allDepartments(), input.departmentIds());
        List<Long> depts = allDepartments ? List.of() : resolveDepartments(input.departmentIds());
        boolean allApplications = everything(input.allApplications(), input.applicationIds());
        List<Long> apps = allApplications ? List.of() : resolveApplications(input.applicationIds());
        requireSoftwareRoleScope(input.role(), kind, allDepartments, allApplications, apps);
        boolean enabled = !Boolean.FALSE.equals(input.enabled());
        requireConsistent(with(nodes, new AccountNode(0, input.username(), parentId, kind, input.role(), enabled, allDepartments,
                Set.copyOf(depts), allApplications, Set.copyOf(apps))), roleFunctions);
        String displayName = blankTo(input.displayName(), input.username());
        long id = accounts.createAccount(input.username(), displayName, input.role(), encode(input.password()), enabled,
                parentId, kind, allDepartments, allApplications);
        accounts.replaceApplicationIds(id, apps);
        accounts.replaceDepartmentIds(id, depts);
        audit.audit(actor.actor(), "ACCOUNT_CREATED", input.username(), null, null,
                "角色 " + input.role() + "，上级 " + parent.username() + kindDetail(kind)
                        + scopeDetail(allDepartments, depts, allApplications, apps));
        return id;
    }

    @Override
    @Transactional
    public void updateAccount(long id, UpdateAccountCommand input, AdminPrincipal actor) {
        accounts.lockAll();
        List<AccountNode> nodes = loadNodes();
        Map<String, Set<String>> roleFunctions = functionsByRole();
        AccountNode current = StructuredAdminHierarchyProcedures.findNode(nodes, id);
        if (current == null) {
            throw ApiException.missing("管理员账号不存在");
        }
        String username = current.username();
        boolean self = id == actor.id();
        if (!self && !StructuredAdminHierarchyProcedures.manages(nodes, actor.id(), id)) {
            throw ApiException.forbidden("只能管理自己下级的管理员账号");
        }
        requireRole(input.role());
        String kind = input.kind() == null ? current.kind() : input.kind();
        boolean keepDepartments = input.allDepartments() == null && input.departmentIds() == null;
        boolean allDepartments = keepDepartments ? current.allDepartments() : everything(input.allDepartments(), input.departmentIds());
        List<Long> depts = allDepartments ? List.of()
                : keepDepartments ? List.copyOf(current.departments()) : resolveDepartments(input.departmentIds());
        boolean keepApplications = input.allApplications() == null && input.applicationIds() == null;
        boolean allApplications = keepApplications ? current.allApplications() : everything(input.allApplications(), input.applicationIds());
        List<Long> apps = allApplications ? List.of()
                : keepApplications ? List.copyOf(current.applications()) : resolveApplications(input.applicationIds());
        Long parentId = requestedParent(input.parentId(), input.parentUsername(), current.parentId());

        boolean roleChanged = !current.role().equals(input.role());
        boolean disabling = current.enabled() && !input.enabled();
        boolean kindChanged = !current.kind().equals(kind);
        boolean parentChanged = !Objects.equals(current.parentId(), parentId);
        boolean scopeChanged = current.allDepartments() != allDepartments || !current.departments().equals(Set.copyOf(depts))
                || current.allApplications() != allApplications || !current.applications().equals(Set.copyOf(apps));
        if (self && (roleChanged || disabling || kindChanged || parentChanged || scopeChanged)) {
            throw ApiException.bad("不能修改自己的角色、类型、管理范围或上级，也不能停用自己的账号");
        }
        if (roleChanged || kindChanged || scopeChanged) {
            requireSoftwareRoleScope(input.role(), kind, allDepartments, allApplications, apps);
        }
        if ((roleChanged || disabling) && current.enabled() && AdminPrincipal.SUPER_ADMIN.equals(current.role())
                && StructuredAdminHierarchyProcedures.enabledSuperAdminCount(nodes) <= 1) {
            throw ApiException.conflict("至少需要保留一个启用的超级管理员");
        }
        List<Long> children = StructuredAdminHierarchyProcedures.childIds(nodes, id);
        if (disabling && !children.isEmpty()) {
            throw ApiException.conflict("该管理员还有 " + children.size() + " 个下级管理员，请先移交或删除下级后再停用");
        }
        AccountNode newParent = null;
        if (parentChanged) {
            newParent = parentId == null ? null : StructuredAdminHierarchyProcedures.findNode(nodes, parentId);
            if (newParent == null) {
                throw ApiException.bad("上级管理员不存在");
            }
            if (parentId != actor.id() && !StructuredAdminHierarchyProcedures.manages(nodes, actor.id(), parentId)) {
                throw ApiException.forbidden("只能移到自己或自己管理的管理员之下");
            }
        }
        requireConsistent(with(nodes, new AccountNode(id, username, parentId, kind, input.role(), input.enabled(),
                allDepartments, Set.copyOf(depts), allApplications, Set.copyOf(apps))), roleFunctions);
        accounts.updateAccount(id, blankTo(input.displayName(), username), input.role(), input.enabled(), kind, allDepartments, allApplications);
        accounts.replaceApplicationIds(id, apps);
        accounts.replaceDepartmentIds(id, depts);
        if (parentChanged) {
            accounts.moveUnder(id, parentId);
        }
        if (roleChanged || disabling || kindChanged) {
            endSessions(id);
        }
        audit.audit(actor.actor(), "ACCOUNT_UPDATED", username, null, null,
                "角色 " + input.role() + (input.enabled() ? "，启用" : "，停用")
                        + (parentChanged ? "，上级改为 " + newParent.username() : "")
                        + kindDetail(kind) + scopeDetail(allDepartments, depts, allApplications, apps));
    }

    private static void requireSoftwareRoleScope(String role, String kind, boolean allDepartments,
                                                 boolean allApplications, List<Long> applications) {
        if (AdminPrincipal.APP_ADMIN.equals(role)
                && (!AdminPrincipal.MANAGER.equals(kind) || !allDepartments || allApplications || applications.isEmpty())) {
            throw ApiException.conflict("内置软件管理员角色需要指定可管理的软件，部门范围为全部部门");
        }
    }

    @Override
    @Transactional
    public void resetPassword(long id, PasswordResetCommand input, AdminPrincipal actor) {
        accounts.lockAll();
        String username = requireManagedAccount(loadNodes(), actor, id).username();
        if (accounts.updatePassword(id, encode(input.password()), nowSec()) == 0) {
            throw ApiException.missing("管理员账号不存在");
        }
        endSessions(id);
        audit.audit(actor.actor(), "ACCOUNT_PASSWORD_RESET", username, null, null, "该账号全部会话已下线");
    }

    @Override
    @Transactional
    public void unlock(long id, AdminPrincipal actor) {
        accounts.lockAll();
        String username = requireManagedAccount(loadNodes(), actor, id).username();
        if (accounts.unlock(id) == 0) {
            throw ApiException.missing("管理员账号不存在");
        }
        audit.audit(actor.actor(), "ACCOUNT_UNLOCKED", username, null, null, "");
    }

    @Override
    @Transactional
    public void deleteAccount(long id, AdminPrincipal actor) {
        if (id == actor.id()) {
            throw ApiException.bad("不能删除当前登录的账号");
        }
        accounts.lockAll();
        List<AccountNode> nodes = loadNodes();
        AccountNode account = requireManagedAccount(nodes, actor, id);
        List<Long> children = StructuredAdminHierarchyProcedures.childIds(nodes, id);
        if (!children.isEmpty()) {
            throw ApiException.conflict("该管理员还有 " + children.size() + " 个下级管理员，请先移交或删除下级");
        }
        if (account.enabled() && AdminPrincipal.SUPER_ADMIN.equals(account.role())
                && StructuredAdminHierarchyProcedures.enabledSuperAdminCount(nodes) <= 1) {
            throw ApiException.conflict("至少需要保留一个启用的超级管理员");
        }
        if (accounts.delete(id) == 0) {
            throw ApiException.missing("管理员账号不存在");
        }
        audit.audit(actor.actor(), "ACCOUNT_DELETED", account.username(), null, null, "");
    }

    @Override
    public void lockTree() {
        accounts.lockAll();
    }

    @Override
    public void requireConsistentWithRole(String role, Set<String> functions) {
        Map<String, Set<String>> roleFunctions = new HashMap<>(functionsByRole());
        roleFunctions.put(role, functions);
        requireConsistent(loadNodes(), roleFunctions);
    }

    private void endSessions(long id) {
        accounts.bumpCredentialVersion(id);
        sessions.deleteForAccount(id);
    }

    private List<AccountNode> loadNodes() {
        Map<Long, Set<Long>> deptMap = new HashMap<>();
        for (AdminAccountRepository.AccountDepartment pair : accounts.departmentPairs()) {
            deptMap.computeIfAbsent(pair.getAccountId(), k -> new TreeSet<>()).add(pair.getDepartmentId());
        }
        Map<Long, Set<Long>> appMap = new HashMap<>();
        for (AdminAccountRepository.AccountApplication pair : accounts.applicationPairs()) {
            appMap.computeIfAbsent(pair.getAccountId(), k -> new TreeSet<>()).add(pair.getApplicationId());
        }
        return accounts.nodes().stream()
                .map(n -> new AccountNode(n.getId(), n.getUsername(), n.getParentId(), n.getKind(), n.getRole(), n.isEnabled(),
                        n.isAllDepartments(), deptMap.getOrDefault(n.getId(), Set.of()),
                        n.isAllApplications(), appMap.getOrDefault(n.getId(), Set.of())))
                .toList();
    }

    private Map<String, Set<String>> functionsByRole() {
        Map<String, Set<String>> result = new HashMap<>();
        for (AdminRole role : roles.listed()) {
            result.put(role.getCode(), Set.copyOf(role.getFunctions()));
        }
        return result;
    }

    private static void requireConsistent(List<AccountNode> nodes, Map<String, Set<String>> roleFunctions) {
        List<String> problems = StructuredAdminHierarchyProcedures.findViolations(nodes, roleFunctions);
        if (!problems.isEmpty()) {
            throw ApiException.conflict("管理员层级不满足要求：" + String.join("；", problems.subList(0, Math.min(3, problems.size()))));
        }
    }

    private static List<AccountNode> with(List<AccountNode> nodes, AccountNode changed) {
        return StructuredAdminHierarchyProcedures.replaceNode(nodes, changed);
    }

    private static AccountNode requireManagedAccount(List<AccountNode> nodes, AdminPrincipal actor, long id) {
        AccountNode node = StructuredAdminHierarchyProcedures.findNode(nodes, id);
        if (node == null) {
            throw ApiException.missing("管理员账号不存在");
        }
        if (!StructuredAdminHierarchyProcedures.manages(nodes, actor.id(), id)) {
            throw ApiException.forbidden("只能管理自己下级的管理员账号");
        }
        return node;
    }

    private Long requestedParent(Long requestedId, String requestedUsername, Long fallback) {
        if (requestedId != null) {
            return requestedId;
        }
        if (requestedUsername == null) {
            return fallback;
        }
        return accounts.credentials(requestedUsername).map(AdminAccountRepository.Credentials::getId)
                .orElseThrow(() -> ApiException.bad("上级管理员不存在"));
    }

    private AdminPrincipal principal(long id, String username, String displayName, String role, String kind, boolean root,
                                     boolean allDepartments, boolean allApplications) {
        AdminRole granted = roles.withFunctions(role).orElseThrow(ApiException::unauthorized);
        EnumSet<AdminFunction> functions = EnumSet.noneOf(AdminFunction.class);
        granted.getFunctions().stream().map(AdminFunction::of).filter(Objects::nonNull).forEach(functions::add);
        Set<Long> depts = allDepartments ? Set.of() : Set.copyOf(accounts.departmentIds(id));
        Set<Long> apps = allApplications ? Set.of() : Set.copyOf(accounts.applicationIds(id));
        return new AdminPrincipal(id, username, blankTo(displayName, username), role, granted.getName(),
                Collections.unmodifiableSet(functions), root, kind, allDepartments, depts, allApplications, apps);
    }

    private void requireRole(String role) {
        if (!roles.existsById(role)) {
            throw ApiException.bad("管理员角色不存在");
        }
    }

    private static boolean everything(Boolean requested, List<Long> listed) {
        return requested != null ? requested : listed == null;
    }

    private List<Long> resolveApplications(List<Long> requested) {
        if (requested == null) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(requested));
        if (!ids.isEmpty() && applications.countByIdIn(ids) != ids.size()) {
            throw ApiException.missing("授权的软件不存在");
        }
        return ids;
    }

    private List<Long> resolveDepartments(List<Long> requested) {
        if (requested == null) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(requested));
        if (!ids.isEmpty() && departments.countByIdIn(ids) != ids.size()) {
            throw ApiException.missing("指定的部门不存在");
        }
        return ids;
    }

    private static String kindDetail(String kind) {
        return AdminPrincipal.DEPARTMENT.equals(kind) ? "，部门管理员" : "";
    }

    private static String scopeDetail(boolean allDepartments, List<Long> departments,
                                      boolean allApplications, List<Long> applications) {
        return (allDepartments ? "" : "，管理部门 " + departments.size() + " 个")
                + (allApplications ? "" : "，授权软件 " + applications.size() + " 个");
    }

    private ApplicationCaller used(long application, long key) {
        usedKeys.add(key);
        return new ApplicationCaller(application, key);
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException | RuntimeException e) {
            return null;
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String encode(String password) {
        if (bytes(password) > PASSWORD_MAX_BYTES) {
            throw ApiException.bad("密码不能超过 72 字节（UTF-8 编码，约 24 个汉字）");
        }
        return encoder.encode(password);
    }

    private boolean matches(String password, String hash) {
        if (password == null || bytes(password) > PASSWORD_MAX_BYTES) {
            encoder.matches("", dummyHash);
            return false;
        }
        return encoder.matches(password, hash);
    }

    private static Instant nowSec() { return Instant.now().truncatedTo(ChronoUnit.SECONDS); }
    private static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
    private static String blankTo(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.strip(); }
    private static ApiException badCredentials() { return ApiException.unauthorized("BAD_CREDENTIALS", "用户名或密码错误"); }
    private ApiException locked() {
        return ApiException.forbidden("ACCOUNT_LOCKED",
                "登录失败次数过多，账号已锁定 " + lockMinutes + " 分钟，可联系超级管理员解锁");
    }
}
