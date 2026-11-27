package com.melonhub.rbac.api.admin;

import com.melonhub.rbac.api.admin.dto.LoginDTO;
import com.melonhub.rbac.api.admin.dto.MfaCodeDTO;
import com.melonhub.rbac.api.admin.dto.MfaDisableDTO;
import com.melonhub.rbac.api.admin.dto.PasswordChangeDTO;
import com.melonhub.rbac.api.admin.vo.LoginVO;
import com.melonhub.rbac.api.admin.vo.MfaSetupVO;
import com.melonhub.rbac.api.admin.vo.PrincipalVO;
import com.melonhub.rbac.api.shared.vo.OkVO;
import com.melonhub.rbac.infrastructure.security.AdminAccess;
import com.melonhub.rbac.service.port.admin.AdminModels.LoginCommand;
import com.melonhub.rbac.service.port.admin.AdminModels.MfaDisableCommand;
import com.melonhub.rbac.service.port.admin.AdminModels.PasswordChangeCommand;
import com.melonhub.rbac.service.port.admin.AuthUseCase;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台管理员登录、当前身份、两步验证与改密接口（{@code /api/auth/**}）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthUseCase auth;

    public AuthController(AuthUseCase auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    public LoginVO login(@Valid @RequestBody LoginDTO body) {
        return AdminVoMapper.toLoginVO(auth.login(new LoginCommand(body.username(), body.password(), body.mfaCode())));
    }

    @GetMapping("/me")
    public PrincipalVO me(HttpServletRequest req) {
        return AdminVoMapper.toPrincipalVO(auth.me(AdminAccess.of(req)));
    }

    @PostMapping("/mfa/setup")
    public MfaSetupVO mfaSetup(HttpServletRequest req) {
        return AdminVoMapper.toMfaSetupVO(auth.setupMfa(AdminAccess.of(req)));
    }

    @PostMapping("/mfa/confirm")
    public OkVO mfaConfirm(HttpServletRequest req, @Valid @RequestBody MfaCodeDTO body) {
        auth.confirmMfa(AdminAccess.of(req), body.code());
        return OkVO.OK;
    }

    @PostMapping("/mfa/disable")
    public OkVO mfaDisable(HttpServletRequest req, @Valid @RequestBody MfaDisableDTO body) {
        auth.disableMfa(AdminAccess.of(req), new MfaDisableCommand(body.password(), body.code()));
        return OkVO.OK;
    }

    @PostMapping("/logout")
    public OkVO logout(HttpServletRequest req, @RequestHeader("Authorization") String header) {
        auth.logout(AdminAccess.of(req), header);
        return OkVO.OK;
    }

    @PutMapping("/password")
    public OkVO password(HttpServletRequest req, @RequestHeader("Authorization") String header,
                         @Valid @RequestBody PasswordChangeDTO body) {
        auth.changePassword(AdminAccess.of(req), header,
                new PasswordChangeCommand(body.currentPassword(), body.newPassword()));
        return OkVO.OK;
    }
}
