package com.melonhub.rbac.r08;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.melonhub.rbac.api.domainrbac.dto.DomainCopyDTO;
import com.melonhub.rbac.api.governance.dto.ManifestDTO;
import com.melonhub.rbac.api.governance.dto.ManifestDepartmentDTO;
import com.melonhub.rbac.api.governance.dto.ManifestPermissionDTO;
import com.melonhub.rbac.api.governance.dto.ManifestRoleDTO;
import com.melonhub.rbac.api.shared.dto.IdsDTO;
import com.melonhub.rbac.api.webhook.dto.WebhookDTO;
import com.melonhub.rbac.shared.RealDbControllerSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * R8 跨部门配置复制与权限清单导出/幂等导入 —— 真实 MySQL Controller 层双策略全量测试。
 */
abstract class R08_DomainTransferControllerContractSupport extends RealDbControllerSupport {

    // =========================================================================
    // 统一测试参数区（路径、清单版本、合法/非法清单载荷与预期错误码）
    // =========================================================================
    private static final String PATH_COPY = "/api/admin/domains/copy";
    private static final String PATH_ROLE_PERMS = "/api/admin/roles/{id}/permissions";
    private static final String PATH_MANIFEST_EXPORT = "/api/admin/manifest";
    private static final String PATH_MANIFEST_IMPORT = "/api/admin/manifest/import";
    private static final String PATH_APP_HOOKS = "/api/admin/applications/{id}/webhooks";

    private static final int VALID_MANIFEST_VERSION = 1;
    private static final int INVALID_MANIFEST_VERSION_0 = 0;
    private static final String PERM_CODE_READ = "doc:read";
    private static final String ROLE_CODE_READER = "doc-reader";
    private static final String HOOK_URL = "https://biz.example.com/manifest-hook";
    private static final String EVENT_ROLE_DISABLED = "role.disabled";
    private static final String UNKNOWN_PERM_CODE = "unknown:perm";
    private static final String UNKNOWN_DEPT_CODE = "no-such-dept-code";

    private static final String ERR_INVALID_REQUEST = "INVALID_REQUEST";
    private static final String ERR_FORBIDDEN = "FORBIDDEN";
    private static final String ERR_NOT_FOUND = "NOT_FOUND";

    @Test
    @DisplayName("R8-01 跨部门复制：首次完整复制权限/角色及关联、二次执行全跳过幂等、同部门400、非法ID400与越权范围403")
    void copyDomainCoversCopyIdempotentSkipValidationAndScope() throws Exception {
        long src = createDepartment("复制源", unique("src"));
        long dst = createDepartment("复制目标", unique("dst"));
        var app = createApplication("CopyApp", unique("ca"));
        long p1 = createPermission(src, app.id(), "读文档", PERM_CODE_READ, "doc", "read");
        long r1 = createRole(src, app.id(), "只读角色", ROLE_CODE_READER, "说明");
        mvc.perform(put(PATH_ROLE_PERMS, r1).param("departmentId", String.valueOf(src))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new IdsDTO(List.of(p1)))))
                .andExpect(status().isOk());

        // 1. 首次复制：新建 1 权限 1 角色
        mvc.perform(post(PATH_COPY).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new DomainCopyDTO(app.id(), src, dst))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissionsCreated").value(1))
                .andExpect(jsonPath("$.permissionsSkipped").value(0))
                .andExpect(jsonPath("$.rolesCreated").value(1))
                .andExpect(jsonPath("$.rolesSkipped").value(0));

        // 2. 二次复制：同编码全部跳过
        mvc.perform(post(PATH_COPY).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new DomainCopyDTO(app.id(), src, dst))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissionsCreated").value(0))
                .andExpect(jsonPath("$.permissionsSkipped").value(1))
                .andExpect(jsonPath("$.rolesCreated").value(0))
                .andExpect(jsonPath("$.rolesSkipped").value(1));

        // 3. 非法：源=目标 400；非正数 ID 400
        mvc.perform(post(PATH_COPY).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new DomainCopyDTO(app.id(), src, src))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));
        mvc.perform(post(PATH_COPY).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new DomainCopyDTO(0L, src, dst))))
                .andExpect(status().isBadRequest());

        // 4. 部门管理员仅管 src 不管 dst -> 403；审计员 -> 403
        var onlySrc = createDeptAdmin(List.of(src));
        mvc.perform(post(PATH_COPY).header("Authorization", onlySrc.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new DomainCopyDTO(app.id(), src, dst))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ERR_FORBIDDEN));
        var auditor = createAuditor();
        mvc.perform(post(PATH_COPY).header("Authorization", auditor.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new DomainCopyDTO(app.id(), src, dst))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("R8-02 权限清单导出（单部门/全可见）与幂等导入（新建/更新）、未知权限400、不存在部门404、范围越权403")
    void manifestExportAndIdempotentImport() throws Exception {
        String code1 = unique("mf-d1");
        String code2 = unique("mf-d2");
        long d1 = createDepartment("清单一部", code1);
        createDepartment("清单二部", code2);
        var app = createApplication("MfApp", unique("mfa"));

        // 1. 首次导入清单 -> 新建 1 权限 1 角色
        var manifestV1 = new ManifestDTO(VALID_MANIFEST_VERSION, List.of(
                new ManifestDepartmentDTO(code1,
                        List.of(new ManifestPermissionDTO(PERM_CODE_READ, "读文档", "doc", "read")),
                        List.of(new ManifestRoleDTO(ROLE_CODE_READER, "只读", "desc", true, List.of(PERM_CODE_READ))))));
        mvc.perform(post(PATH_MANIFEST_IMPORT).param("applicationId", String.valueOf(app.id()))
                        .header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(manifestV1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissionsCreated").value(1))
                .andExpect(jsonPath("$.rolesCreated").value(1));

        // 2. 注册 Webhook 并二次导入停用该角色 -> 幂等更新 1 权限 1 角色且触发 role.disabled 通知
        mvc.perform(post(PATH_APP_HOOKS, app.id()).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new WebhookDTO("清单变更通知", HOOK_URL, null, List.of(EVENT_ROLE_DISABLED), true))))
                .andExpect(status().isOk());
        var manifestUpdated = new ManifestDTO(VALID_MANIFEST_VERSION, List.of(
                new ManifestDepartmentDTO(code1,
                        List.of(new ManifestPermissionDTO(PERM_CODE_READ, "读文档改", "doc", "read")),
                        List.of(new ManifestRoleDTO(ROLE_CODE_READER, "只读改", "new", false, List.of(PERM_CODE_READ))))));
        mvc.perform(post(PATH_MANIFEST_IMPORT).param("applicationId", String.valueOf(app.id()))
                        .header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(manifestUpdated)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissionsUpdated").value(1))
                .andExpect(jsonPath("$.rolesUpdated").value(1));
        Integer statusEvents = db.queryForObject(
                "SELECT COUNT(*) FROM webhook_deliveries d JOIN webhooks w ON w.id = d.webhook_id WHERE w.application_id = ? AND d.event_type = ?",
                Integer.class, app.id(), EVENT_ROLE_DISABLED);
        assertThat(statusEvents).isGreaterThanOrEqualTo(1);

        // 3. 导出单部门清单与全软件清单
        mvc.perform(get(PATH_MANIFEST_EXPORT).param("applicationId", String.valueOf(app.id()))
                        .param("departmentId", String.valueOf(d1)).header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(VALID_MANIFEST_VERSION))
                .andExpect(jsonPath("$.departments[0].code").value(code1))
                .andExpect(jsonPath("$.departments[0].roles[0].enabled").value(false));
        mvc.perform(get(PATH_MANIFEST_EXPORT).param("applicationId", String.valueOf(app.id()))
                        .header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departments.length()").value(1));

        // 4. 非法清单：version<=0 -> 400；departments=null -> 400；角色引用未知权限 -> 400；未知部门编码 -> 404
        mvc.perform(post(PATH_MANIFEST_IMPORT).param("applicationId", String.valueOf(app.id()))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ManifestDTO(INVALID_MANIFEST_VERSION_0, manifestV1.departments()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));
        mvc.perform(post(PATH_MANIFEST_IMPORT).param("applicationId", String.valueOf(app.id()))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ManifestDTO(VALID_MANIFEST_VERSION, null))))
                .andExpect(status().isBadRequest());
        mvc.perform(post(PATH_MANIFEST_IMPORT).param("applicationId", String.valueOf(app.id()))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ManifestDTO(VALID_MANIFEST_VERSION, List.of(
                                new ManifestDepartmentDTO(code1, List.of(),
                                        List.of(new ManifestRoleDTO(ROLE_CODE_READER, "R", "", true, List.of(UNKNOWN_PERM_CODE)))))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));
        mvc.perform(post(PATH_MANIFEST_IMPORT).param("applicationId", String.valueOf(app.id()))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ManifestDTO(VALID_MANIFEST_VERSION, List.of(
                                new ManifestDepartmentDTO(UNKNOWN_DEPT_CODE, List.of(), List.of()))))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ERR_NOT_FOUND));

        // 5. 部门管理员仅管 d1，导入包含 d2 的清单 -> 403
        var onlyD1 = createDeptAdmin(List.of(d1));
        mvc.perform(post(PATH_MANIFEST_IMPORT).param("applicationId", String.valueOf(app.id()))
                        .header("Authorization", onlyD1.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ManifestDTO(VALID_MANIFEST_VERSION, List.of(
                                new ManifestDepartmentDTO(code2, List.of(), List.of()))))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ERR_FORBIDDEN));
    }
}
