package com.melonhub.rbac.api.admin;

import static com.melonhub.rbac.service.port.security.AdminFunction.ACCOUNT_MANAGE;

import com.melonhub.rbac.api.admin.dto.AccountCreateDTO;
import com.melonhub.rbac.api.admin.dto.AccountUpdateDTO;
import com.melonhub.rbac.api.admin.dto.PasswordResetDTO;
import com.melonhub.rbac.api.admin.vo.AccountCreatedVO;
import com.melonhub.rbac.api.admin.vo.AdminAccountVO;
import com.melonhub.rbac.api.shared.vo.OkVO;
import com.melonhub.rbac.infrastructure.security.AdminAccess;
import com.melonhub.rbac.infrastructure.security.RequiresFunction;
import com.melonhub.rbac.service.port.admin.AdminModels.CreateAccountCommand;
import com.melonhub.rbac.service.port.admin.AdminModels.PasswordResetCommand;
import com.melonhub.rbac.service.port.admin.AdminModels.UpdateAccountCommand;
import com.melonhub.rbac.service.port.admin.AuthUseCase;
import com.melonhub.rbac.service.port.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台管理员账号层级管理接口（{@code /api/admin/accounts/**}）。
 */
@RestController
@RequestMapping("/api/admin/accounts")
public class AccountController {
    private final AuthUseCase auth;

    public AccountController(AuthUseCase auth) {
        this.auth = auth;
    }

    private static long accountId(long id) {
        if (id < 1) {
            throw ApiException.bad("管理员 ID 必须为正整数");
        }
        return id;
    }

    @RequiresFunction(ACCOUNT_MANAGE)
    @GetMapping
    public List<AdminAccountVO> list(HttpServletRequest req) {
        return auth.accounts(AdminAccess.of(req)).stream().map(AdminVoMapper::toAdminAccountVO).toList();
    }

    @RequiresFunction(ACCOUNT_MANAGE)
    @PostMapping
    public AccountCreatedVO create(@Valid @RequestBody AccountCreateDTO body, HttpServletRequest req) {
        long id = auth.createAccount(new CreateAccountCommand(
                body.username(), body.displayName(), body.role(), body.password(), body.enabled(),
                body.applicationIds(), body.allApplications(), body.parentId(), body.parentUsername(),
                body.kind(), body.allDepartments(), body.departmentIds()), AdminAccess.of(req));
        return new AccountCreatedVO(id, body.username());
    }

    @RequiresFunction(ACCOUNT_MANAGE)
    @PutMapping("/{id}")
    public OkVO update(@PathVariable long id, @Valid @RequestBody AccountUpdateDTO body, HttpServletRequest req) {
        auth.updateAccount(accountId(id), new UpdateAccountCommand(
                body.displayName(), body.role(), body.enabled(), body.applicationIds(), body.allApplications(),
                body.parentId(), body.parentUsername(), body.kind(), body.allDepartments(), body.departmentIds()),
                AdminAccess.of(req));
        return OkVO.OK;
    }

    @RequiresFunction(ACCOUNT_MANAGE)
    @PutMapping("/{id}/password")
    public OkVO reset(@PathVariable long id, @Valid @RequestBody PasswordResetDTO body, HttpServletRequest req) {
        auth.resetPassword(accountId(id), new PasswordResetCommand(body.password()), AdminAccess.of(req));
        return OkVO.OK;
    }

    @RequiresFunction(ACCOUNT_MANAGE)
    @PostMapping("/{id}/mfa/reset")
    public OkVO resetMfa(@PathVariable long id, HttpServletRequest req) {
        auth.resetMfa(accountId(id), AdminAccess.of(req));
        return OkVO.OK;
    }

    @RequiresFunction(ACCOUNT_MANAGE)
    @PostMapping("/{id}/unlock")
    public OkVO unlock(@PathVariable long id, HttpServletRequest req) {
        auth.unlock(accountId(id), AdminAccess.of(req));
        return OkVO.OK;
    }

    @RequiresFunction(ACCOUNT_MANAGE)
    @DeleteMapping("/{id}")
    public OkVO delete(@PathVariable long id, HttpServletRequest req) {
        auth.deleteAccount(accountId(id), AdminAccess.of(req));
        return OkVO.OK;
    }
}
