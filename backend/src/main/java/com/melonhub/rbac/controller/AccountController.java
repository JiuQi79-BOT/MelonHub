package com.melonhub.rbac.controller;

import com.melonhub.rbac.dto.Requests;
import com.melonhub.rbac.exception.ApiException;
import com.melonhub.rbac.security.AdminAccess;
import com.melonhub.rbac.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Console operator accounts; only super administrators may manage them. */
@RestController
@RequestMapping("/api/admin/accounts")
public class AccountController {
    private final AuthService auth;
    public AccountController(AuthService auth) { this.auth = auth; }

    private static String username(String value) {
        if (!value.matches(Requests.USERNAME)) throw ApiException.bad("账号名格式不正确");
        return value;
    }

    @GetMapping List<Map<String,Object>> list(HttpServletRequest req) { AdminAccess.superAdmin(req); return auth.accounts(); }
    @PostMapping Map<String,Object> create(@Valid @RequestBody Requests.Account body, HttpServletRequest req) {
        auth.createAccount(body, AdminAccess.superAdmin(req));
        return Map.of("username", body.username());
    }
    @PutMapping("/{username}") Map<String,Object> update(@PathVariable String username, @Valid @RequestBody Requests.AccountUpdate body, HttpServletRequest req) {
        auth.updateAccount(username(username), body, AdminAccess.superAdmin(req));
        return Map.of("ok", true);
    }
    @PutMapping("/{username}/password") Map<String,Object> reset(@PathVariable String username, @Valid @RequestBody Requests.PasswordReset body, HttpServletRequest req) {
        auth.resetPassword(username(username), body, AdminAccess.superAdmin(req));
        return Map.of("ok", true);
    }
    @PostMapping("/{username}/mfa/reset") Map<String,Object> resetMfa(@PathVariable String username, HttpServletRequest req) {
        auth.resetMfa(username(username), AdminAccess.superAdmin(req));
        return Map.of("ok", true);
    }
    @PostMapping("/{username}/unlock") Map<String,Object> unlock(@PathVariable String username, HttpServletRequest req) {
        auth.unlock(username(username), AdminAccess.superAdmin(req));
        return Map.of("ok", true);
    }
    @DeleteMapping("/{username}") Map<String,Object> delete(@PathVariable String username, HttpServletRequest req) {
        auth.deleteAccount(username(username), AdminAccess.superAdmin(req));
        return Map.of("ok", true);
    }
}
