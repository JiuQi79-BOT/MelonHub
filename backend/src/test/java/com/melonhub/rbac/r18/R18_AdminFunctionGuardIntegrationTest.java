package com.melonhub.rbac.r18;

import static org.assertj.core.api.Assertions.assertThat;

import com.melonhub.rbac.infrastructure.security.RequiresFunction;
import com.melonhub.rbac.service.port.security.AdminFunction;
import com.melonhub.rbac.shared.CloudTestDatabaseGuard;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** 控制台路由注解守卫测试：核对全部 /api/admin 接口均声明 @RequiresFunction 且内置角色保持既有权限矩阵。对应需求：R18（见 doc/requirements.md）。 */
@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(initializers = CloudTestDatabaseGuard.class)
class R18_AdminFunctionGuardIntegrationTest {
    // 统一测试参数区
    private static final String ADMIN_PATH_PREFIX = "/api/admin";
    private static final int MIN_CONSOLE_ENDPOINTS = 73;
    private static final int LEGACY_MATRIX_SIZE = 73;
    private static final Set<String> ALL = Set.of("SUPER_ADMIN", "ADMIN", "APP_ADMIN", "AUDITOR");
    /** Which built-in roles passed each former check (AdminAccess.of/readApplication/writeApplication/platformWrite/superAdmin). */
    private static final Map<String, Set<String>> FORMER_CHECK = Map.of(
            "ANY", ALL, "READ", ALL, "WRITE_APP", Set.of("SUPER_ADMIN", "ADMIN", "APP_ADMIN"),
            "PLATFORM", Set.of("SUPER_ADMIN", "ADMIN"), "SUPER", Set.of("SUPER_ADMIN"));
    /** The check every console endpoint made before functions existed, taken from the controllers at that time. */
    private static final String FORMER_ACCESS = """
            GET /api/admin/departments ANY
            POST /api/admin/departments PLATFORM
            PUT /api/admin/departments/{id} PLATFORM
            DELETE /api/admin/departments/{id} PLATFORM
            GET /api/admin/applications ANY
            POST /api/admin/applications PLATFORM
            PUT /api/admin/applications/{id} PLATFORM
            PATCH /api/admin/applications/{id}/status PLATFORM
            DELETE /api/admin/applications/{id} PLATFORM
            GET /api/admin/applications/{id}/keys READ
            POST /api/admin/applications/{id}/keys WRITE_APP
            DELETE /api/admin/applications/{id}/keys/{keyId} WRITE_APP
            POST /api/admin/applications/{id}/rotate-key WRITE_APP
            GET /api/admin/users ANY
            GET /api/admin/users/export ANY
            POST /api/admin/users PLATFORM
            PUT /api/admin/users/{id} PLATFORM
            PATCH /api/admin/users/{id}/status PLATFORM
            DELETE /api/admin/users/{id} PLATFORM
            GET /api/admin/users/{id}/access ANY
            DELETE /api/admin/users/{id}/contexts PLATFORM
            GET /api/admin/roles READ
            POST /api/admin/roles WRITE_APP
            PUT /api/admin/roles/{id} WRITE_APP
            PATCH /api/admin/roles/{id}/status WRITE_APP
            DELETE /api/admin/roles/{id} WRITE_APP
            PUT /api/admin/roles/{id}/permissions WRITE_APP
            GET /api/admin/roles/{id}/members READ
            PUT /api/admin/roles/{id}/members WRITE_APP
            GET /api/admin/permissions READ
            POST /api/admin/permissions WRITE_APP
            PUT /api/admin/permissions/{id} WRITE_APP
            DELETE /api/admin/permissions/{id} WRITE_APP
            GET /api/admin/users/{id}/roles READ
            PUT /api/admin/users/{id}/roles WRITE_APP
            GET /api/admin/assignments READ
            POST /api/admin/assignments/batch WRITE_APP
            POST /api/admin/domains/copy WRITE_APP
            POST /api/admin/simulate READ
            GET /api/admin/overview READ
            GET /api/admin/audit/export ANY
            GET /api/admin/audit ANY
            GET /api/admin/access-requests READ
            POST /api/admin/access-requests/{id}/approve WRITE_APP
            POST /api/admin/access-requests/{id}/reject WRITE_APP
            GET /api/admin/reviews READ
            POST /api/admin/reviews WRITE_APP
            GET /api/admin/reviews/{id} READ
            GET /api/admin/reviews/{id}/items READ
            PUT /api/admin/reviews/{id}/items/{itemId} WRITE_APP
            POST /api/admin/reviews/{id}/close WRITE_APP
            GET /api/admin/applications/{id}/webhooks READ
            POST /api/admin/applications/{id}/webhooks WRITE_APP
            PUT /api/admin/webhooks/{id} WRITE_APP
            DELETE /api/admin/webhooks/{id} WRITE_APP
            GET /api/admin/webhooks/{id}/deliveries READ
            POST /api/admin/webhooks/{id}/test WRITE_APP
            GET /api/admin/manifest READ
            POST /api/admin/manifest/import WRITE_APP
            POST /api/admin/users/import PLATFORM
            GET /api/admin/permissions/who READ
            GET /api/admin/assignments/expiring READ
            PUT /api/admin/applications/{id}/rate-limit PLATFORM
            GET /api/admin/search ANY
            GET /api/admin/system/status ANY
            GET /api/admin/audit/verify ANY
            GET /api/admin/accounts SUPER
            POST /api/admin/accounts SUPER
            PUT /api/admin/accounts/{id} SUPER
            PUT /api/admin/accounts/{id}/password SUPER
            POST /api/admin/accounts/{id}/mfa/reset SUPER
            POST /api/admin/accounts/{id}/unlock SUPER
            DELETE /api/admin/accounts/{id} SUPER
            """;

    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @Autowired JdbcTemplate db;

    private Map<String, HandlerMethod> consoleHandlers() {
        var handlers = new TreeMap<String, HandlerMethod>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mappings.getHandlerMethods().entrySet()) {
            var info = entry.getKey();
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                if (!pattern.startsWith(ADMIN_PATH_PREFIX)) continue;
                for (var method : info.getMethodsCondition().getMethods()) handlers.put(method + " " + pattern, entry.getValue());
            }
        }
        return handlers;
    }

    @DisplayName("R18 AdminFunction 19 项枚举与数据库 admin_functions 表一致")
    @Test void functionCatalogMatchesTheDatabase() {
        var recorded = new TreeSet<>(db.queryForList("SELECT code FROM admin_functions", String.class));
        var declared = Arrays.stream(AdminFunction.values()).map(AdminFunction::code).collect(Collectors.toCollection(TreeSet::new));
        assertThat(declared).isEqualTo(recorded);
    }

    @DisplayName("R18 每个 /api/admin 控制台接口都声明 @RequiresFunction 管理功能")
    @Test void everyConsoleEndpointDeclaresAFunction() {
        var handlers = consoleHandlers();
        assertThat(handlers).hasSizeGreaterThanOrEqualTo(MIN_CONSOLE_ENDPOINTS);
        var undeclared = handlers.entrySet().stream()
                .filter(e -> e.getValue().getMethodAnnotation(RequiresFunction.class) == null
                        || e.getValue().getMethodAnnotation(RequiresFunction.class).value().length == 0)
                .map(Map.Entry::getKey).toList();
        assertThat(undeclared).isEmpty();
    }

    @DisplayName("R18 内置管理员角色权限与既有规则及 platformAdminOnly 一致")
    @Test void builtinRolesKeepTheAccessOfTheFormerFixedRoles() {
        var granted = new HashMap<String, Set<String>>();
        for (var row : db.queryForList("SELECT role_code,function_code FROM admin_role_functions WHERE role_code IN ('SUPER_ADMIN','ADMIN','APP_ADMIN','AUDITOR')"))
            granted.computeIfAbsent((String) row.get("role_code"), k -> new TreeSet<>()).add((String) row.get("function_code"));
        var handlers = consoleHandlers();
        var mismatches = new ArrayList<String>();
        List<String> lines = FORMER_ACCESS.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        assertThat(lines).hasSize(LEGACY_MATRIX_SIZE);
        for (String line : lines) {
            String endpoint = line.substring(0, line.lastIndexOf(' '));
            var expected = FORMER_CHECK.get(line.substring(line.lastIndexOf(' ') + 1));
            var handler = handlers.get(endpoint);
            if (handler == null) { mismatches.add(endpoint + ": endpoint no longer exists"); continue; }
            var functions = Arrays.stream(handler.getMethodAnnotation(RequiresFunction.class).value()).map(AdminFunction::code).toList();
            var allowed = ALL.stream().filter(role -> functions.stream().anyMatch(granted.getOrDefault(role, Set.of())::contains))
                    .collect(Collectors.toCollection(TreeSet::new));
            if (!allowed.equals(new TreeSet<>(expected))) mismatches.add(endpoint + ": allowed " + allowed + ", formerly " + new TreeSet<>(expected));
        }
        assertThat(mismatches).isEmpty();
    }
}
