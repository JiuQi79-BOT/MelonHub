package com.melonhub.rbac.service.port.admin;

import com.melonhub.rbac.service.port.admin.AdminModels.AdminFunctionResult;
import com.melonhub.rbac.service.port.admin.AdminModels.AdminRoleResult;
import com.melonhub.rbac.service.port.admin.AdminModels.CreateAdminRoleCommand;
import com.melonhub.rbac.service.port.admin.AdminModels.UpdateAdminRoleCommand;
import com.melonhub.rbac.service.port.security.AdminPrincipal;
import java.util.List;

/**
 * 控制台管理员功能清单与自定义管理员角色维护用例端口。
 */
public interface AdminRoleUseCase {
    List<AdminFunctionResult> functions();

    List<AdminRoleResult> roles();

    void create(CreateAdminRoleCommand input, AdminPrincipal actor);

    void update(String code, UpdateAdminRoleCommand input, AdminPrincipal actor);

    void delete(String code, AdminPrincipal actor);
}
