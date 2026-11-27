package com.melonhub.rbac.r31;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import cn.edu.xmu.clonefactory.util.CloneFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.melonhub.rbac.api.admin.vo.AdminAccountVO;
import com.melonhub.rbac.api.admin.vo.AdminRoleVO;
import com.melonhub.rbac.api.admin.vo.LoginVO;
import com.melonhub.rbac.api.admin.vo.PrincipalVO;
import com.melonhub.rbac.api.application.vo.ApiKeyCreatedVO;
import com.melonhub.rbac.api.application.vo.ApiKeyVO;
import com.melonhub.rbac.api.application.vo.ApplicationCreatedVO;
import com.melonhub.rbac.api.application.vo.ApplicationVO;
import com.melonhub.rbac.api.audit.vo.AuditEventVO;
import com.melonhub.rbac.api.audit.vo.AuditSealStateVO;
import com.melonhub.rbac.api.audit.vo.AuditVerifyVO;
import com.melonhub.rbac.api.console.vo.ConsoleCountsVO;
import com.melonhub.rbac.api.console.vo.HealthVO;
import com.melonhub.rbac.api.console.vo.SearchApplicationVO;
import com.melonhub.rbac.api.console.vo.SearchDepartmentVO;
import com.melonhub.rbac.api.console.vo.SearchResultsVO;
import com.melonhub.rbac.api.console.vo.SearchRoleVO;
import com.melonhub.rbac.api.console.vo.SearchUserVO;
import com.melonhub.rbac.api.console.vo.SystemStatusVO;
import com.melonhub.rbac.api.context.vo.CheckVO;
import com.melonhub.rbac.api.context.vo.ContextRoleVO;
import com.melonhub.rbac.api.context.vo.ContextVO;
import com.melonhub.rbac.api.domainrbac.vo.BatchUserRolesResultVO;
import com.melonhub.rbac.api.domainrbac.vo.DomainAssignmentVO;
import com.melonhub.rbac.api.domainrbac.vo.DomainCopyResultVO;
import com.melonhub.rbac.api.domainrbac.vo.DomainOverviewVO;
import com.melonhub.rbac.api.domainrbac.vo.PermissionVO;
import com.melonhub.rbac.api.domainrbac.vo.RoleMemberVO;
import com.melonhub.rbac.api.domainrbac.vo.RoleVO;
import com.melonhub.rbac.api.domainrbac.vo.SimulatedPermissionVO;
import com.melonhub.rbac.api.domainrbac.vo.SimulatedRoleVO;
import com.melonhub.rbac.api.domainrbac.vo.SimulationVO;
import com.melonhub.rbac.api.domainrbac.vo.UserRoleAssignmentsVO;
import com.melonhub.rbac.api.governance.vo.AccessRequestVO;
import com.melonhub.rbac.api.governance.vo.ExpiringAssignmentVO;
import com.melonhub.rbac.api.governance.vo.ManifestApplicationVO;
import com.melonhub.rbac.api.governance.vo.ManifestDepartmentVO;
import com.melonhub.rbac.api.governance.vo.ManifestImportResultVO;
import com.melonhub.rbac.api.governance.vo.ManifestPermissionVO;
import com.melonhub.rbac.api.governance.vo.ManifestRoleVO;
import com.melonhub.rbac.api.governance.vo.PermissionHolderRoleVO;
import com.melonhub.rbac.api.governance.vo.PermissionHolderVO;
import com.melonhub.rbac.api.governance.vo.PermissionHoldersVO;
import com.melonhub.rbac.api.governance.vo.PermissionManifestVO;
import com.melonhub.rbac.api.governance.vo.ReviewDetailVO;
import com.melonhub.rbac.api.governance.vo.ReviewItemVO;
import com.melonhub.rbac.api.governance.vo.ReviewSummaryVO;
import com.melonhub.rbac.api.governance.vo.SelfAccessRoleVO;
import com.melonhub.rbac.api.governance.vo.SelfAccessVO;
import com.melonhub.rbac.api.identity.vo.CourseAuditActorVO;
import com.melonhub.rbac.api.identity.vo.CourseAuditEventVO;
import com.melonhub.rbac.api.identity.vo.IdentityPageVO;
import com.melonhub.rbac.api.identity.vo.IdentityPermissionCheckVO;
import com.melonhub.rbac.api.organization.vo.AccessDomainRoleVO;
import com.melonhub.rbac.api.organization.vo.AccessDomainVO;
import com.melonhub.rbac.api.organization.vo.ActiveContextVO;
import com.melonhub.rbac.api.organization.vo.DepartmentUserVO;
import com.melonhub.rbac.api.organization.vo.DepartmentVO;
import com.melonhub.rbac.api.organization.vo.UserAccessVO;
import com.melonhub.rbac.api.organization.vo.UserImportFailureVO;
import com.melonhub.rbac.api.organization.vo.UserImportResultVO;
import com.melonhub.rbac.api.organization.vo.UserSummaryVO;
import com.melonhub.rbac.api.organization.vo.UserVO;
import com.melonhub.rbac.api.shared.error.ApiErrors;
import com.melonhub.rbac.api.shared.vo.PageVO;
import com.melonhub.rbac.api.webhook.vo.WebhookCreatedVO;
import com.melonhub.rbac.api.webhook.vo.WebhookDeliveryVO;
import com.melonhub.rbac.api.webhook.vo.WebhookVO;
import com.melonhub.rbac.service.port.context.ContextModels.CheckResult;
import com.melonhub.rbac.service.port.context.ContextModels.ContextResult;
import com.melonhub.rbac.service.port.context.ContextModels.ContextRoleResult;
import com.melonhub.rbac.shared.JsonShapeSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * 纯单元测试：验证全部 51 项强类型 api.*.vo.* 响应 VO 经与 Spring 同配置的 Jackson 序列化后，
 * 与 api-shapes.json 契约逐字段、逐时间格式一致，并覆盖 CloneFactory、统一身份与错误载荷契约（覆盖 R31）。
 */
class R31_DtoContractShapeUnitTest {

    // =========================================================================
    // 统一测试参数区（契约文件路径、时区、固定时间戳与样本常量）
    // =========================================================================
    private static final Path RECORDED_SHAPES_PATH = JsonShapeSupport.recordedShapesPath();
    private static final String TZ_SHANGHAI = "Asia/Shanghai";
    private static final String TZ_UTC = "UTC";
    private static final Instant SAMPLE_TS = Instant.parse("2026-11-24T08:00:00Z");
    private static final Instant SAMPLE_TS_MILLIS = Instant.parse("2026-11-24T08:00:00.123456Z");
    private static final Instant SAMPLE_EXPIRY = Instant.parse("2026-12-24T08:00:00Z");
    private static final String EXPECTED_SHANGHAI_MILLIS = "2026-11-24T16:00:00.123+08:00";
    private static final String EXPECTED_UTC_MILLIS = "2026-11-24T08:00:00.123+00:00";
    private static final String EXPECTED_SHANGHAI_ZERO = "2026-11-24T16:00:00.000+08:00";
    private static final String SAMPLE_REQUEST_ID = "req-001";
    private static final String SAMPLE_PERM_MCO = "MCO";
    private static final String SAMPLE_ERR_CODE = "FORBIDDEN";
    private static final String SAMPLE_ERR_MESSAGE = "无权限";

    @Test
    @DisplayName("R31 全部 51 项 api.*.vo.* 强类型响应 VO 序列化形状与 api-shapes.json 逐项一致")
    void allTypedVoClassesMatchRecordedApiShapesExactly() throws Exception {
        ObjectMapper mapper = JsonShapeSupport.springCompatibleMapper(TZ_SHANGHAI);
        JsonNode expected = mapper.readTree(Files.readString(RECORDED_SHAPES_PATH));

        var pendingRequest = new AccessRequestVO(
                1L, 10L, "Contract User", "ext-1", 2L, "Contract One", 3L, "Contract App",
                4L, "Reader", "reader", true, "contract", 7, "PENDING", "app:3",
                null, null, "", null, SAMPLE_TS);
        var approvedRequest = new AccessRequestVO(
                1L, 10L, "Contract User", "ext-1", 2L, "Contract One", 3L, "Contract App",
                4L, "Reader", "reader", true, "contract", 7, "APPROVED", "app:3",
                "admin", SAMPLE_TS, "approved", SAMPLE_EXPIRY, SAMPLE_TS);
        var userVO = new UserVO(
                10L, "Contract User", "ext-1", "u1@example.com", true, null, 1L, SAMPLE_TS, List.of(2L, 3L));
        var departmentVO = new DepartmentVO(2L, "Contract One", "cd1-1", SAMPLE_TS, 2L, 2L);
        var roleVO = new RoleVO(
                4L, "Reader", "reader", "reads items", true, SAMPLE_TS, 2L, List.of(5L), List.of(6L));
        var permissionVO = new PermissionVO(
                5L, "Read", "item:read", "item", "read", SAMPLE_TS, 1L);
        var auditEvent = new AuditEventVO(
                100L, "contract", "ROLE_UPDATED", "Reader", "detail", "127.0.0.1", SAMPLE_TS,
                2L, "Contract One", 3L, "Contract App");
        var auditEventNoDept = new AuditEventVO(
                101L, "contract", "APP_CREATED", "Contract App", "detail", "127.0.0.1", SAMPLE_TS,
                null, null, 3L, "Contract App");
        var openReview = new ReviewDetailVO(
                7L, "Contract review", 3L, "Contract App", 2L, "Contract One", "OPEN",
                SAMPLE_EXPIRY, "admin", SAMPLE_TS, null, null, "", 2L, 2L, 0L, 0L, 0L);
        var closedReview = new ReviewDetailVO(
                7L, "Contract review", 3L, "Contract App", 2L, "Contract One", "CLOSED",
                SAMPLE_EXPIRY, "admin", SAMPLE_TS, "admin", SAMPLE_TS, "回收 0 条", 2L, 0L, 2L, 0L, 0L);
        var reviewSummary = new ReviewSummaryVO(
                7L, "Contract review", 3L, "Contract App", 2L, "Contract One", "OPEN",
                SAMPLE_EXPIRY, "admin", SAMPLE_TS, null, null, 2L, 2L, 0L, 0L, 0L);
        var roleAssignment = new UserRoleAssignmentsVO(List.of(4L), Map.of(4L, SAMPLE_EXPIRY));

        ContextVO contextIssued = CloneFactory.copy(new ContextVO(), new ContextResult(
                "ctx-token", 10L, 2L, 3L, 1L, SAMPLE_EXPIRY,
                List.of(new ContextRoleResult(4L, "Reader", "reader")), List.of("item:read")));
        contextIssued.setRoles(List.of(new ContextRoleVO(4L, "Reader", "reader")));

        ContextVO contextEffective = CloneFactory.copy(new ContextVO(), new ContextResult(
                null, 10L, 2L, 3L, 1L, SAMPLE_EXPIRY,
                List.of(new ContextRoleResult(4L, "Reader", "reader")), List.of("item:read")));
        contextEffective.setRoles(List.of(new ContextRoleVO(4L, "Reader", "reader")));

        CheckVO checkVO = CloneFactory.copy(new CheckVO(), new CheckResult(
                false, Map.of("item:read", true, "item:delete", false), 1L));

        Map<String, Object> samples = new TreeMap<>();
        samples.put("GET /api/admin/access-requests", new PageVO<>(List.of(pendingRequest), 1L, 1, 20));
        samples.put("GET /api/admin/accounts", List.of(new AdminAccountVO(
                1L, "contract-1", "Contract", "SUPER_ADMIN", true, 0, 0L, null,
                SAMPLE_TS, SAMPLE_TS, SAMPLE_TS, 1L, 0L, true, 0L, "admin", "MANAGER", true, List.of(), List.of(), true)));
        samples.put("GET /api/admin/applications", List.of(new ApplicationVO(
                3L, "Contract App", "ca-1", "desc", "owner", true, null, SAMPLE_TS, 2L, 2L, 1L)));
        samples.put("GET /api/admin/applications/{id}/keys", List.of(
                new ApiKeyVO(1L, "default", "mh_1234", "admin", SAMPLE_TS, null, null, null, "ACTIVE"),
                new ApiKeyVO(2L, "second", "mh_5678", "admin", SAMPLE_TS, SAMPLE_EXPIRY, null, null, "ACTIVE")));
        samples.put("GET /api/admin/applications/{id}/webhooks", List.of(new WebhookVO(
                1L, "hook", "http://127.0.0.1:1/hook", List.of("*"), true, "admin", SAMPLE_TS, 1L, 0L, null)));
        samples.put("GET /api/admin/assignments", new PageVO<>(List.of(
                new DomainAssignmentVO(10L, "User1", "ext-1", "u1@example.com", true, List.of(4L), Map.of(4L, SAMPLE_EXPIRY)),
                new DomainAssignmentVO(11L, "User2", "ext-2", "", true, List.of(4L), Map.of())), 2L, 1, 20));
        samples.put("GET /api/admin/assignments/expiring", List.of(new ExpiringAssignmentVO(
                10L, "User", "ext-1", 4L, "Reader", "reader", 2L, "Dept", 3L, "App", SAMPLE_EXPIRY, "admin", SAMPLE_TS)));
        samples.put("GET /api/admin/audit", new PageVO<>(List.of(auditEvent, auditEventNoDept), 2L, 1, 50));
        samples.put("GET /api/admin/audit/export", new PageVO<>(List.of(auditEvent, auditEventNoDept), 2L, 1, 50));
        samples.put("GET /api/admin/departments", List.of(departmentVO));
        samples.put("GET /api/admin/manifest", new PermissionManifestVO(
                1, new ManifestApplicationVO("ca-1", "Contract App"),
                "2026-11-24T08:00:00.123456789Z",
                List.of(new ManifestDepartmentVO("cd1-1", "Contract One",
                        List.of(new ManifestPermissionVO("item:read", "Read", "item", "read")),
                        List.of(new ManifestRoleVO("reader", "Reader", "", true, List.of("item:read")))))));
        samples.put("GET /api/admin/overview", new DomainOverviewVO(1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 0, 5));
        samples.put("GET /api/admin/permissions", List.of(permissionVO));
        samples.put("GET /api/admin/permissions/who", new PermissionHoldersVO(
                "item:read", 3L,
                List.of(new PermissionHolderVO(10L, "User", "ext-1", 2L, "Dept", List.of(
                        new PermissionHolderRoleVO(4L, "Reader", "reader", SAMPLE_EXPIRY),
                        new PermissionHolderRoleVO(5L, "Writer", "writer", null)))),
                1L, false));
        samples.put("GET /api/admin/reviews", List.of(reviewSummary));
        samples.put("GET /api/admin/reviews/{id}", openReview);
        samples.put("GET /api/admin/reviews/{id}/items", new PageVO<>(List.of(
                new ReviewItemVO(1L, 10L, "User", "ext-1", 4L, "Reader", "reader", 2L, "Dept",
                        "PENDING", "", null, null, false, false)), 1L, 1, 20));
        samples.put("GET /api/admin/roles", List.of(roleVO));
        samples.put("GET /api/admin/roles/{id}/members", List.of(new RoleMemberVO(10L, "User", "ext-1", "u1@example.com", true)));
        samples.put("GET /api/admin/search", new SearchResultsVO(
                List.of(new SearchUserVO(10L, "User", "ext-1", true)),
                List.of(new SearchDepartmentVO(2L, "Dept", "cd1")),
                List.of(new SearchApplicationVO(3L, "App", "ca1", true)),
                List.of(new SearchRoleVO(4L, "Reader", "reader", 2L, "Dept", 3L, "App"))));
        samples.put("GET /api/admin/system/status", new SystemStatusVO(
                "1.0.0", 42L, "21", "DISABLED", "UP", 3L,
                new ConsoleCountsVO(2, 2, 2, 1, 1, 2, 2, 2, 0),
                1L, 0L, 0L, 1L,
                new AuditSealStateVO(10L, 10L, "a".repeat(64), 10L, 0L, SAMPLE_TS, 0L, 10L)));
        samples.put("GET /api/admin/users", new PageVO<>(List.of(userVO), 1L, 1, 20));
        samples.put("GET /api/admin/users/export", new PageVO<>(List.of(userVO), 1L, 1, 20));
        samples.put("GET /api/admin/users/{id}/access", new UserAccessVO(
                userVO,
                List.of(new AccessDomainVO(2L, "Dept", 3L, "App", true,
                        List.of(new AccessDomainRoleVO(4L, "Reader", "reader", true, SAMPLE_EXPIRY, "admin")),
                        List.of("item:read"))),
                List.of()));
        samples.put("GET /api/admin/users/{id}/roles", roleAssignment);
        samples.put("GET /api/admin/webhooks/{id}/deliveries", new PageVO<>(List.of(
                new WebhookDeliveryVO(1L, "evt-1", "role.updated", "PENDING", 0, SAMPLE_TS, null, "", SAMPLE_TS, null)), 1L, 1, 20));
        samples.put("GET /api/auth/me", new PrincipalVO(
                1L, "admin", "Admin", "SUPER_ADMIN", "超级管理员", List.of("org.view"), true, "MANAGER", true, List.of(), true, List.of(), false));
        samples.put("GET /api/health", new HealthVO("UP", "UP", "DISABLED", "RBAC1"));
        samples.put("GET /api/v1/access-requests", List.of(pendingRequest));
        samples.put("GET /api/v1/contexts/effective", contextEffective);
        samples.put("GET /api/v1/departments", List.of(departmentVO));
        samples.put("GET /api/v1/departments/{dept}/permissions", List.of(permissionVO));
        samples.put("GET /api/v1/departments/{dept}/roles", List.of(roleVO));
        samples.put("GET /api/v1/departments/{dept}/users", List.of(new DepartmentUserVO(10L, "User", "ext-1", true)));
        samples.put("GET /api/v1/departments/{dept}/users/{id}/roles", roleAssignment);
        samples.put("GET /api/v1/users/access", new SelfAccessVO(
                10L, 2L, 3L,
                List.of(new SelfAccessRoleVO(4L, "Reader", "reader", true, SAMPLE_EXPIRY)),
                List.of("item:read")));
        samples.put("POST /api/admin/access-requests/{id}/approve", approvedRequest);
        samples.put("POST /api/admin/applications", new ApplicationCreatedVO(3L, "mh_secret", 1L));
        samples.put("POST /api/admin/applications/{id}/keys", new ApiKeyCreatedVO(2L, "second", "mh_5678", "mh_secret2", SAMPLE_EXPIRY));
        samples.put("POST /api/admin/applications/{id}/webhooks", new WebhookCreatedVO(1L, "whsec_abc"));
        samples.put("POST /api/admin/assignments/batch", new BatchUserRolesResultVO(1, 1, 1));
        samples.put("POST /api/admin/domains/copy", new DomainCopyResultVO(1, 1, 1, 1));
        samples.put("POST /api/admin/manifest/import", new ManifestImportResultVO(1, 1, 1, 1));
        samples.put("POST /api/admin/reviews", openReview);
        samples.put("POST /api/admin/reviews/{id}/close", closedReview);
        samples.put("POST /api/admin/simulate", new SimulationVO(
                true, List.of(), List.of(),
                new UserSummaryVO(10L, "User", "ext-1", "u1@example.com", true, null, 1L, SAMPLE_TS),
                true, true, false,
                List.of(new SimulatedRoleVO(4L, "Reader", "reader", true, SAMPLE_EXPIRY, false, true)),
                List.of(new SimulatedPermissionVO(5L, "item:read", "Read", "item", "read", List.of(4L))),
                new LinkedHashMap<>(Map.of("item:read", true, "item:write", true, "item:delete", false)),
                false));
        samples.put("POST /api/admin/users/import", new UserImportResultVO(
                1, 0, List.of(new UserImportFailureVO(2, "bad-1", "部门不存在"))));
        samples.put("POST /api/auth/login", new LoginVO(
                "tok", 28800, 1800, 1L, "admin", "Admin", "SUPER_ADMIN", "超级管理员",
                List.of("org.view"), true, "MANAGER", true, List.of(), true, List.of(), false));
        samples.put("POST /api/v1/access-requests", pendingRequest);
        samples.put("POST /api/v1/contexts", contextIssued);
        samples.put("POST /api/v1/contexts/check", checkVO);

        TreeSet<String> expectedLabels = new TreeSet<>();
        expected.fieldNames().forEachRemaining(expectedLabels::add);
        assertThat(samples.keySet()).containsExactlyElementsOf(expectedLabels);

        List<String> problems = new ArrayList<>();
        for (String label : expectedLabels) {
            JsonNode actualJson = mapper.readTree(mapper.writeValueAsString(samples.get(label)));
            JsonNode actualShape = JsonShapeSupport.shape(actualJson);
            JsonNode wantShape = expected.get(label);
            if (!wantShape.equals(actualShape)) {
                problems.add(label + ":\n    want: " + wantShape + "\n    got:  " + actualShape);
            }
        }
        if (!problems.isEmpty()) {
            fail("Typed VO JSON shapes diverged from api-shapes.json:\n" + String.join("\n", problems));
        }
    }

    @Test
    @DisplayName("R24/R25/R31 统一身份视图、精确时间格式、扩展视图与 ErrorBody 可选字段契约准确")
    void identityAndOptionalFieldContractsSerializeCleanly() throws Exception {
        ObjectMapper shanghai = JsonShapeSupport.springCompatibleMapper(TZ_SHANGHAI);
        ObjectMapper utc = JsonShapeSupport.springCompatibleMapper(TZ_UTC);

        JsonNode deptShanghai = shanghai.readTree(shanghai.writeValueAsString(
                new DepartmentVO(1L, "研发部", "dev", SAMPLE_TS_MILLIS, 1L, 1L)));
        assertThat(deptShanghai.get("createdAt").asText()).isEqualTo(EXPECTED_SHANGHAI_MILLIS);
        JsonNode deptUtc = utc.readTree(utc.writeValueAsString(
                new DepartmentVO(1L, "研发部", "dev", SAMPLE_TS_MILLIS, 1L, 1L)));
        assertThat(deptUtc.get("createdAt").asText()).isEqualTo(EXPECTED_UTC_MILLIS);

        JsonNode assignmentsJson = shanghai.readTree(shanghai.writeValueAsString(
                new UserRoleAssignmentsVO(List.of(4L, 5L), Map.of(4L, SAMPLE_TS_MILLIS))));
        assertThat(assignmentsJson.get("roleExpiries").get("4").asText()).isEqualTo(EXPECTED_SHANGHAI_MILLIS);
        assertThat(assignmentsJson.get("roleExpiries").has("5")).isFalse();

        JsonNode activeCtx = shanghai.readTree(shanghai.writeValueAsString(
                new ActiveContextVO(2L, "研发部", 3L, "应用", SAMPLE_TS, SAMPLE_EXPIRY, 2L, 1L)));
        assertThat(activeCtx.get("valid").isIntegralNumber()).isTrue();
        assertThat(activeCtx.get("valid").asLong()).isEqualTo(1L);
        assertThat(activeCtx.get("createdAt").asText()).isEqualTo(EXPECTED_SHANGHAI_ZERO);

        JsonNode permanentKey = shanghai.readTree(shanghai.writeValueAsString(
                new ApiKeyCreatedVO(1L, "perm", "mh_1", "mh_secret", null)));
        assertThat(permanentKey.get("expiresAt").isNull()).isTrue();

        ContextVO effectiveVo = CloneFactory.copy(new ContextVO(),
                new ContextResult(null, 10L, 2L, 3L, 1L, SAMPLE_EXPIRY, List.of(), List.of()));
        effectiveVo.setRoles(List.of());
        JsonNode effectiveJson = shanghai.readTree(shanghai.writeValueAsString(effectiveVo));
        assertThat(effectiveJson.has("contextToken")).isFalse();
        assertThat(effectiveJson.get("expiresAt").asText()).isEqualTo("2026-12-24T08:00:00Z");

        JsonNode adminRole = shanghai.readTree(shanghai.writeValueAsString(
                new AdminRoleVO("OPS", "运维", "", false, List.of("org.view"), 2L, SAMPLE_TS)));
        assertThat(adminRole.get("createdAt").asText()).isEqualTo(EXPECTED_SHANGHAI_ZERO);
        JsonNode verifyVO = shanghai.readTree(shanghai.writeValueAsString(new AuditVerifyVO(
                true, null, 10L, 1L, 10L, 1L, 10L, null, null, true, null,
                new AuditSealStateVO(10L, 10L, "0".repeat(64), 10L, 0L, SAMPLE_TS, 0L, 10L))));
        assertThat(verifyVO.get("ok").asBoolean()).isTrue();
        assertThat(verifyVO.get("seal").get("updatedAt").asText()).isEqualTo(EXPECTED_SHANGHAI_ZERO);

        JsonNode singlePerm = shanghai.readTree(shanghai.writeValueAsString(
                new IdentityPermissionCheckVO(SAMPLE_PERM_MCO, true)));
        List<String> permFields = new ArrayList<>();
        singlePerm.fieldNames().forEachRemaining(permFields::add);
        assertThat(permFields).containsExactlyInAnyOrder("permission", "allowed");
        assertThat(singlePerm.get("permission").asText()).isEqualTo(SAMPLE_PERM_MCO);
        assertThat(singlePerm.get("allowed").asBoolean()).isTrue();

        JsonNode courseAudit = shanghai.readTree(shanghai.writeValueAsString(new IdentityPageVO<>(
                1, 20, 1L, List.of(new CourseAuditEventVO(
                        99L, "2026-11-20T08:00:00Z",
                        new CourseAuditActorVO(null, "OLD_TEACHER", List.of(), true),
                        "score_changed", "课程甲")))));
        assertThat(courseAudit.get("items").get(0).get("actor").get("id").isNull()).isTrue();
        assertThat(courseAudit.get("items").get(0).get("actor").get("deleted").asBoolean()).isTrue();

        JsonNode simNoChecks = shanghai.readTree(shanghai.writeValueAsString(new SimulationVO(
                true, List.of(), List.of(),
                new UserSummaryVO(1L, "U", "u1", "", true, null, 0L, SAMPLE_TS),
                true, true, false, List.of(), List.of(), null, null)));
        assertThat(simNoChecks.has("decisions")).isFalse();
        assertThat(simNoChecks.has("allowed")).isFalse();
        assertThat(simNoChecks.get("user").has("departmentIds")).isFalse();

        MDC.remove("requestId");
        JsonNode errNoId = shanghai.readTree(shanghai.writeValueAsString(ApiErrors.body(SAMPLE_ERR_CODE, SAMPLE_ERR_MESSAGE)));
        assertThat(errNoId.has("requestId")).isFalse();
        assertThat(errNoId.get("code").asText()).isEqualTo(SAMPLE_ERR_CODE);

        MDC.put("requestId", SAMPLE_REQUEST_ID);
        try {
            JsonNode errWithId = shanghai.readTree(shanghai.writeValueAsString(ApiErrors.body(SAMPLE_ERR_CODE, SAMPLE_ERR_MESSAGE)));
            assertThat(errWithId.get("requestId").asText()).isEqualTo(SAMPLE_REQUEST_ID);
        } finally {
            MDC.remove("requestId");
        }
    }
}
