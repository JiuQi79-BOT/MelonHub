package com.melonhub.rbac.r25;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.melonhub.rbac.api.domainrbac.dto.UserRolesDTO;
import com.melonhub.rbac.api.identity.dto.IdentityLoginDTO;
import com.melonhub.rbac.api.organization.dto.IdentityPasswordDTO;
import com.melonhub.rbac.api.shared.dto.IdsDTO;
import com.melonhub.rbac.shared.RealDbControllerSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * R25 课程系统专属审计日志隔离、AV 权限校验、已删除操作人保留历史标识与非法查询边界 —— 真实 MySQL Controller 层双策略全量测试。
 */
abstract class R25_CourseAuditControllerContractSupport extends RealDbControllerSupport {

    // =========================================================================
    // 统一测试参数区（路径、目标系统、合法/非法分页与时间区间及预期错误码）
    // =========================================================================
    private static final String PATH_ID_LOGIN = "/auth/login";
    private static final String PATH_COURSE_AUDIT = "/auth/audit-logs";
    private static final String PATH_SET_PASSWORD = "/api/admin/users/{id}/identity-password";
    private static final String PATH_ROLE_PERMS = "/api/admin/roles/{id}/permissions";
    private static final String PATH_USER_ROLES = "/api/admin/users/{id}/roles";
    private static final String PATH_USER_BY_ID = "/api/admin/users/{id}";

    private static final String SYSTEM_COURSE = "course-project-management";
    private static final String COURSE_AUDIT_ACTION = "score_changed";
    private static final String PLATFORM_AUDIT_ACTION = "USER_ROLES_UPDATED";
    private static final String VALID_PASSWORD = "AuditViewer!2026";
    private static final String RANGE_FROM_AFTER = "2026-11-30T00:00:00Z";
    private static final String RANGE_TO_BEFORE = "2026-11-01T00:00:00Z";

    private static final String ERR_INVALID_REQUEST = "INVALID_REQUEST";
    private static final String ERR_FORBIDDEN = "FORBIDDEN";

    private long ensureCourseAppId() throws Exception {
        List<Long> found = db.queryForList("SELECT id FROM applications WHERE code = ?", Long.class, SYSTEM_COURSE);
        if (!found.isEmpty()) {
            return found.get(0);
        }
        return createApplication("课程项目管理平台", SYSTEM_COURSE).id();
    }

    @Test
    @DisplayName("R25-01 仅 AV 权限可见管辖部门课程审计、已删操作人标 deleted=true、无 AV 返 403、未登录 401 与非法分页/时间 400")
    void courseAuditLogsEnforceAvPermissionDeletedActorAndValidation() throws Exception {
        long courseAppId = ensureCourseAppId();
        var otherApp = createApplication("OtherApp", unique("otha"));
        long d1 = createDepartment("课程审计一部", unique("cad1"));
        long d2 = createDepartment("课程审计二部", unique("cad2"));

        String viewerExt = unique("AVU");
        String noAvExt = unique("NOAV");
        String deletedActorExt = unique("DELACT");
        long viewerId = createUser("审计教师", viewerExt, "", List.of(d1));
        long noAvId = createUser("普通教师", noAvExt, "", List.of(d1));
        long deletedActorId = createUser("离职教师", deletedActorExt, "", List.of(d1));

        for (long uid : List.of(viewerId, noAvId)) {
            mvc.perform(put(PATH_SET_PASSWORD, uid).header("Authorization", superBearer)
                            .contentType(MediaType.APPLICATION_JSON).content(json(new IdentityPasswordDTO(VALID_PASSWORD))))
                    .andExpect(status().isOk());
        }

        long pAv = createPermission(d1, courseAppId, "查看审计", "AV", "audit", "view");
        long rAv = createRole(d1, courseAppId, "审计角色", "auditor-role", "");
        mvc.perform(put(PATH_ROLE_PERMS, rAv).param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(courseAppId))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON).content(json(new IdsDTO(List.of(pAv)))))
                .andExpect(status().isOk());
        mvc.perform(put(PATH_USER_ROLES, viewerId).param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(courseAppId))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON).content(json(new UserRolesDTO(List.of(rAv), null, null))))
                .andExpect(status().isOk());

        // 写入 3 条真实审计：d1 课程审计（由即将删除的教师操作）、d2 课程审计（不可见）、d1 其他软件审计（不可见）
        String courseTarget = "课程-" + tag;
        db.update("INSERT INTO audit_events(actor, action, target, department_id, application_id, detail, source_ip) VALUES (?, ?, ?, ?, ?, '', '127.0.0.1')",
                deletedActorExt, COURSE_AUDIT_ACTION, courseTarget, d1, courseAppId);
        db.update("INSERT INTO audit_events(actor, action, target, department_id, application_id, detail, source_ip) VALUES (?, ?, ?, ?, ?, '', '127.0.0.1')",
                viewerExt, COURSE_AUDIT_ACTION, "二部课程-" + tag, d2, courseAppId);
        db.update("INSERT INTO audit_events(actor, action, target, department_id, application_id, detail, source_ip) VALUES (?, ?, ?, ?, ?, '', '127.0.0.1')",
                viewerExt, COURSE_AUDIT_ACTION, "其他软件-" + tag, d1, otherApp.id());
        db.update("INSERT INTO audit_events(actor, action, target, department_id, application_id, detail, source_ip) VALUES (?, ?, ?, ?, ?, '', '127.0.0.1')",
                viewerExt, PLATFORM_AUDIT_ACTION, "平台操作-" + tag, d1, courseAppId);
        // 删除操作人账号 -> 审计仍保留历史 actor 标识并标记 deleted=true
        mvc.perform(delete(PATH_USER_BY_ID, deletedActorId).header("Authorization", superBearer)).andExpect(status().isOk());

        String viewerJwt = parseJson(mvc.perform(post(PATH_ID_LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new IdentityLoginDTO(viewerExt, VALID_PASSWORD, List.of(SYSTEM_COURSE)))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("accessToken").asText();
        String viewerBearer = "Bearer " + viewerJwt;

        // 1. 仅返回 d1 课程系统审计，且已删操作人保留 id=null, name=deletedActorExt, deleted=true
        mvc.perform(get(PATH_COURSE_AUDIT).param("target", tag).header("Authorization", viewerBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].target").value(courseTarget))
                .andExpect(jsonPath("$.items[0].actor.id").isEmpty())
                .andExpect(jsonPath("$.items[0].actor.name").value(deletedActorExt))
                .andExpect(jsonPath("$.items[0].actor.deleted").value(true));

        // 2. 非法查询参数：page=0、pageSize=0/101、actorId=0、from>to -> 400
        mvc.perform(get(PATH_COURSE_AUDIT).param("page", "0").header("Authorization", viewerBearer))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));
        mvc.perform(get(PATH_COURSE_AUDIT).param("pageSize", "0").header("Authorization", viewerBearer))
                .andExpect(status().isBadRequest());
        mvc.perform(get(PATH_COURSE_AUDIT).param("pageSize", "101").header("Authorization", viewerBearer))
                .andExpect(status().isBadRequest());
        mvc.perform(get(PATH_COURSE_AUDIT).param("actorId", "0").header("Authorization", viewerBearer))
                .andExpect(status().isBadRequest());
        mvc.perform(get(PATH_COURSE_AUDIT).param("from", RANGE_FROM_AFTER).param("to", RANGE_TO_BEFORE)
                        .header("Authorization", viewerBearer))
                .andExpect(status().isBadRequest());

        // 3. 无 AV 权限 -> 403；未登录 -> 401
        String noAvJwt = parseJson(mvc.perform(post(PATH_ID_LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new IdentityLoginDTO(noAvExt, VALID_PASSWORD, List.of(SYSTEM_COURSE)))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("accessToken").asText();
        mvc.perform(get(PATH_COURSE_AUDIT).header("Authorization", "Bearer " + noAvJwt))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ERR_FORBIDDEN));
        mvc.perform(get(PATH_COURSE_AUDIT))
                .andExpect(status().isUnauthorized());
    }
}
