package com.melonhub.rbac.controller;

import com.melonhub.rbac.dto.Requests;
import com.melonhub.rbac.security.AdminAccess;
import com.melonhub.rbac.service.AuthService;
import com.melonhub.rbac.service.RbacService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class AuthController {
    private final RbacService rbac;
    private final AuthService auth;
    public AuthController(RbacService rbac, AuthService auth) { this.rbac = rbac; this.auth = auth; }

    @GetMapping("/api/health") ResponseEntity<Map<String,Object>> health() {
        var health = rbac.health();
        return ResponseEntity.status("UP".equals(health.get("status")) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(health);
    }
    @PostMapping("/api/auth/login") Map<String,Object> login(@Valid @RequestBody Requests.Login body) { return auth.login(body); }
    @GetMapping("/api/auth/me") Map<String,Object> me(HttpServletRequest req) { return auth.me(AdminAccess.of(req)); }
    @PostMapping("/api/auth/mfa/setup") Map<String,Object> mfaSetup(HttpServletRequest req) { return auth.setupMfa(AdminAccess.of(req)); }
    @PostMapping("/api/auth/mfa/confirm") Map<String,Object> mfaConfirm(HttpServletRequest req, @Valid @RequestBody Requests.MfaCode body) {
        auth.confirmMfa(AdminAccess.of(req), body.code());
        return Map.of("ok", true);
    }
    @PostMapping("/api/auth/mfa/disable") Map<String,Object> mfaDisable(HttpServletRequest req, @Valid @RequestBody Requests.MfaDisable body) {
        auth.disableMfa(AdminAccess.of(req), body);
        return Map.of("ok", true);
    }
    @PostMapping("/api/auth/logout") Map<String,Object> logout(HttpServletRequest req, @RequestHeader("Authorization") String header) {
        auth.logout(AdminAccess.of(req), header);
        return Map.of("ok", true);
    }
    @PutMapping("/api/auth/password") Map<String,Object> password(HttpServletRequest req, @RequestHeader("Authorization") String header,
                                                                 @Valid @RequestBody Requests.PasswordChange body) {
        auth.changePassword(AdminAccess.of(req), header, body);
        return Map.of("ok", true);
    }
}
