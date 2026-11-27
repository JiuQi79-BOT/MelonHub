package com.melonhub.rbac.api.identity;

import static com.melonhub.rbac.service.port.security.AdminFunction.USER_CREDENTIALS;
import static com.melonhub.rbac.service.port.security.AdminFunction.USER_MANAGE;

import com.melonhub.rbac.api.identity.dto.IdentityUserImportDTO;
import com.melonhub.rbac.api.identity.dto.IdentityUserRowDTO;
import com.melonhub.rbac.api.identity.vo.IdentityImportResultVO;
import com.melonhub.rbac.api.identity.vo.IdentityImportRowVO;
import com.melonhub.rbac.infrastructure.security.AdminAccess;
import com.melonhub.rbac.infrastructure.security.RequiresFunction;
import com.melonhub.rbac.service.port.error.ApiException;
import com.melonhub.rbac.service.port.identity.IdentityUseCase;
import com.melonhub.rbac.service.port.organization.OrganizationModels.SaveUserCommand;
import com.melonhub.rbac.service.port.security.AdminPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统一身份联调用户与初始凭据批量导入管理接口（{@code /api/admin/identity/**}）。
 */
@RestController
@RequestMapping("/api/admin/identity")
public class IdentityAdminController {
    private final IdentityUseCase identityService;

    public IdentityAdminController(IdentityUseCase identityService) {
        this.identityService = identityService;
    }

    @RequiresFunction(USER_CREDENTIALS)
    @PostMapping("/users/import")
    public IdentityImportResultVO importUsers(@Valid @RequestBody IdentityUserImportDTO body, HttpServletRequest request) {
        AdminPrincipal principal = AdminAccess.allDepartments(request);
        if (!principal.canUse(USER_MANAGE)) {
            throw ApiException.forbidden("批量导入还需要维护用户功能");
        }
        List<IdentityImportRowVO> rows = new ArrayList<>();
        for (IdentityUserRowDTO row : body.users()) {
            SaveUserCommand user = new SaveUserCommand(row.name(), row.username(), row.email(), row.departmentIds());
            long id = identityService.provisionUser(user, row.password(), principal.actor());
            rows.add(new IdentityImportRowVO(row.username(), id, "imported"));
        }
        return new IdentityImportResultVO(rows.size(), rows);
    }
}
