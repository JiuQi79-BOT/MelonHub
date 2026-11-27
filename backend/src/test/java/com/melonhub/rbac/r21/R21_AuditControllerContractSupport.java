package com.melonhub.rbac.r21;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.melonhub.rbac.api.admin.dto.AdminRoleCreateDTO;
import com.melonhub.rbac.service.port.common.PageResult;
import com.melonhub.rbac.service.port.security.AdminPrincipal;
import com.melonhub.rbac.shared.RealDbControllerSupport;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * R21 审计日志分页/导出、部门可见范围隔离与 SHA-256 哈希链封存校验（完整/篡改/断链） —— 真实 MySQL Controller 层双策略全量测试。
 */
abstract class R21_AuditControllerContractSupport extends RealDbControllerSupport {

    // =========================================================================
    // 统一测试参数区（路径、日期筛选参数、封存与校验参数及预期错误码）
    // =========================================================================
    private static final String PATH_AUDIT = "/api/admin/audit";
    private static final String PATH_AUDIT_EXPORT = "/api/admin/audit/export";
    private static final String PATH_AUDIT_VERIFY = "/api/admin/audit/verify";
    private static final String PATH_ADMIN_ROLES = "/api/admin/admin-roles";

    private static final String INVALID_DATE = "not-a-date";
    private static final int EXPORT_MAX_SIZE = 5000;
    private static final int VERIFY_OVER_LIMIT = 99_999;
    private static final String PROBLEM_MODIFIED = "MODIFIED";
    private static final String PROBLEM_LINK_BROKEN = "LINK_BROKEN";
    private static final String PROBLEM_TAIL_MISSING = "TAIL_MISSING";
    private static final String ERR_INVALID_REQUEST = "INVALID_REQUEST";
    private static final String ERR_FORBIDDEN = "FORBIDDEN";

    @Test
    @DisplayName("R21-01 审计日志分页/日期筛选/导出上限 5000、非法日期 400、部门管理员仅见管辖部门审计、缺 audit.view 403")
    void auditListExportDateValidationAndScopeIsolation() throws Exception {
        long d1 = createDepartment("审计一部", unique("au1"));
        long d2 = createDepartment("审计二部", unique("au2"));
        var app = createApplication("AudApp", unique("aua"));
        createRole(d1, app.id(), "一部角色-" + tag, "r1-" + tag, "");
        createRole(d2, app.id(), "二部角色-" + tag, "r2-" + tag, "");

        String today = LocalDate.now(PageResult.ZONE).toString();
        var auditor = createAuditor();

        // 1. 审计员正常查询（含 page=0/size=999 边界钳制）与导出（size=5000）
        mvc.perform(get(PATH_AUDIT).param("keyword", tag).param("from", today).param("to", today)
                        .param("page", "0").param("size", "999").header("Authorization", auditor.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(200))
                .andExpect(jsonPath("$.items[*].departmentId", hasItem((int) d1)));
        mvc.perform(get(PATH_AUDIT_EXPORT).param("keyword", tag).header("Authorization", auditor.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(EXPORT_MAX_SIZE));

        // 2. 非法日期格式 -> 400
        mvc.perform(get(PATH_AUDIT).param("from", INVALID_DATE).header("Authorization", auditor.bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));

        // 3. 部门管理员仅见管辖部门 d1 的审计，不可见 d2
        var dept1Admin = createDeptAdmin(List.of(d1));
        mvc.perform(get(PATH_AUDIT).param("keyword", tag).header("Authorization", dept1Admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].departmentId", hasItem((int) d1)))
                .andExpect(jsonPath("$.items[*].departmentId", not(hasItem((int) d2))));

        // 4. 缺少 audit.view 的自定义管理员 -> 403
        String noAuditRole = ("NOAUD_" + tag).toUpperCase();
        mvc.perform(post(PATH_ADMIN_ROLES).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AdminRoleCreateDTO(noAuditRole, "无审计", "", List.of("org.view")))))
                .andExpect(status().isOk());
        var noAuditAdmin = createAdminAndLogin("noaud", noAuditRole, AdminPrincipal.MANAGER, null, null);
        mvc.perform(get(PATH_AUDIT).header("Authorization", noAuditAdmin.bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ERR_FORBIDDEN));
    }

    @Test
    @DisplayName("R21-02 审计哈希链封存校验：完整链 ok=true、实库篡改记录检出 MODIFIED、实库篡改前哈希检出 LINK_BROKEN")
    void auditHashChainVerifyDetectsIntactModifiedAndLinkBroken() throws Exception {
        createDepartment("封存部门-" + tag, unique("seal"));
        auditSealUseCase.sealNow();
        Long lastSeq = db.queryForObject("SELECT last_seq FROM audit_seal WHERE id = 1", Long.class);
        Long targetId = db.queryForObject("SELECT id FROM audit_events WHERE seal_seq = ?", Long.class, lastSeq);

        // 1. 封存后完整校验（含超大 limit 钳制）-> ok=true, complete=true
        mvc.perform(get(PATH_AUDIT_VERIFY).param("fromSeq", String.valueOf(lastSeq))
                        .param("limit", String.valueOf(VERIFY_OVER_LIMIT))
                        .header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.complete").value(true));

        // 2. 篡改记录 actor 字段 -> 检出 MODIFIED
        db.update("UPDATE audit_events SET actor = 'tampered-actor' WHERE id = ?", targetId);
        mvc.perform(get(PATH_AUDIT_VERIFY).param("fromSeq", String.valueOf(lastSeq)).param("limit", "100")
                        .header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.problem").value(PROBLEM_MODIFIED));

        // 3. 篡改 prev_hash -> 检出 LINK_BROKEN
        db.update("UPDATE audit_events SET prev_hash = ? WHERE id = ?", "f".repeat(64), targetId);
        mvc.perform(get(PATH_AUDIT_VERIFY).param("fromSeq", String.valueOf(lastSeq)).param("limit", "100")
                        .header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.problem").value(PROBLEM_LINK_BROKEN));

        // 4. 删除链尾已封存记录 -> 检出 TAIL_MISSING
        db.update("DELETE FROM audit_events WHERE id = ?", targetId);
        mvc.perform(get(PATH_AUDIT_VERIFY).param("fromSeq", String.valueOf(lastSeq)).param("limit", "100")
                        .header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.problem").value(PROBLEM_TAIL_MISSING));
    }
}
