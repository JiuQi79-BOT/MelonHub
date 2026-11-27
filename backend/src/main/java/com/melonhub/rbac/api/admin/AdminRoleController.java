package com.melonhub.rbac.api.admin;

import static com.melonhub.rbac.service.port.security.AdminFunction.ACCOUNT_MANAGE;
import static com.melonhub.rbac.service.port.security.AdminFunction.ROLE_MANAGE;

import com.melonhub.rbac.api.admin.dto.AdminRoleCreateDTO;
import com.melonhub.rbac.api.admin.dto.AdminRoleUpdateDTO;
import com.melonhub.rbac.api.admin.vo.AdminFunctionVO;
import com.melonhub.rbac.api.admin.vo.AdminRoleVO;
import com.melonhub.rbac.api.shared.dto.ValidationPatterns;
import com.melonhub.rbac.api.shared.vo.CodeVO;
import com.melonhub.rbac.api.shared.vo.OkVO;
import com.melonhub.rbac.infrastructure.security.AdminAccess;
import com.melonhub.rbac.infrastructure.security.RequiresFunction;
import com.melonhub.rbac.service.port.admin.AdminModels.CreateAdminRoleCommand;
import com.melonhub.rbac.service.port.admin.AdminModels.UpdateAdminRoleCommand;
import com.melonhub.rbac.service.port.admin.AdminRoleUseCase;
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
 * 控制台平台功能目录与管理员角色配置接口。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminRoleController {
    private final AdminRoleUseCase roles;

    public AdminRoleController(AdminRoleUseCase roles) {
        this.roles = roles;
    }

    private static String code(String value) {
        if (!value.matches(ValidationPatterns.ADMIN_ROLE_CODE)) {
            throw ApiException.bad("角色编码格式不正确");
        }
        return value;
    }

    @RequiresFunction({ACCOUNT_MANAGE, ROLE_MANAGE})
    @GetMapping("/admin-functions")
    public List<AdminFunctionVO> functions(HttpServletRequest req) {
        AdminAccess.of(req);
        return roles.functions().stream().map(AdminVoMapper::toAdminFunctionVO).toList();
    }

    @RequiresFunction({ACCOUNT_MANAGE, ROLE_MANAGE})
    @GetMapping("/admin-roles")
    public List<AdminRoleVO> list(HttpServletRequest req) {
        AdminAccess.of(req);
        return roles.roles().stream().map(AdminVoMapper::toAdminRoleVO).toList();
    }

    @RequiresFunction(ROLE_MANAGE)
    @PostMapping("/admin-roles")
    public CodeVO create(@Valid @RequestBody AdminRoleCreateDTO body, HttpServletRequest req) {
        roles.create(new CreateAdminRoleCommand(body.code(), body.name(), body.description(), body.functions()),
                AdminAccess.of(req));
        return new CodeVO(body.code());
    }

    @RequiresFunction(ROLE_MANAGE)
    @PutMapping("/admin-roles/{code}")
    public OkVO update(@PathVariable String code, @Valid @RequestBody AdminRoleUpdateDTO body, HttpServletRequest req) {
        roles.update(code(code), new UpdateAdminRoleCommand(body.name(), body.description(), body.functions()),
                AdminAccess.of(req));
        return OkVO.OK;
    }

    @RequiresFunction(ROLE_MANAGE)
    @DeleteMapping("/admin-roles/{code}")
    public OkVO delete(@PathVariable String code, HttpServletRequest req) {
        roles.delete(code(code), AdminAccess.of(req));
        return OkVO.OK;
    }
}
