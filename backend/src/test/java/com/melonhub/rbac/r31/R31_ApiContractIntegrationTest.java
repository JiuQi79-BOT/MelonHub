package com.melonhub.rbac.r31;

import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.melonhub.rbac.api.context.dto.PermissionCheckDTO;
import com.melonhub.rbac.infrastructure.security.Credentials;
import com.melonhub.rbac.service.port.admin.AdminModels.CreateAccountCommand;
import com.melonhub.rbac.service.port.admin.AuthUseCase;
import com.melonhub.rbac.service.port.application.ApplicationModels.CreateApiKeyCommand;
import com.melonhub.rbac.service.port.application.ApplicationModels.SaveApplicationCommand;
import com.melonhub.rbac.service.port.context.IssueContextCommand;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.BatchUserRolesCommand;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.DomainCopyCommand;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.SavePermissionCommand;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.SaveRoleCommand;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.SimulationCommand;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacUseCase;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ApproveRequestCommand;
import com.melonhub.rbac.service.port.governance.GovernanceModels.CreateReviewCommand;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ManifestDepartmentCommand;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ManifestImportCommand;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ManifestPermissionCommand;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ManifestRoleCommand;
import com.melonhub.rbac.service.port.governance.GovernanceModels.SubmitAccessRequestCommand;
import com.melonhub.rbac.service.port.governance.GovernanceModels.UserImportCommand;
import com.melonhub.rbac.service.port.governance.GovernanceModels.UserImportRowCommand;
import com.melonhub.rbac.service.port.organization.OrganizationModels.SaveDepartmentCommand;
import com.melonhub.rbac.service.port.organization.OrganizationModels.SaveUserCommand;
import com.melonhub.rbac.service.port.organization.OrganizationUseCase;
import com.melonhub.rbac.service.port.security.AdminPrincipal;
import com.melonhub.rbac.service.port.webhook.WebhookModels.SaveWebhookCommand;
import com.melonhub.rbac.shared.CloudTestDatabaseGuard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 55 个核心接口 JSON 响应结构契约锁，保证前后端与 Java SDK 协议在重构和双策略切换下严格一致。
 * 有意变更字段时可带 {@code -Dcontract.update=true} 重新录制并审阅 diff。
 * 对应需求：R31（见 doc/requirements.md）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
@ContextConfiguration(initializers = CloudTestDatabaseGuard.class)
class R31_ApiContractIntegrationTest {
    // 统一测试参数区
    private static final Path RECORDED = Path.of("src/test/resources/contract/api-shapes.json");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}([T ]\\d{2}:\\d{2}.*)?");
    private static final Pattern NUMERIC_KEY = Pattern.compile("\\d+");
    private static final String ACTOR = "contract";
    private static final String PASSWORD = "Contract-Test-Passw0rd";
    private static final String PERM_READ = "item:read";
    private static final String PERM_WRITE = "item:write";
    private static final String PERM_DELETE = "item:delete";
    private static final String PERM_EXPORT = "item:export";
    private static final String HOOK_URL = "http://127.0.0.1:1/hook";
    private static final int KEY_EXPIRE_DAYS = 90;
    private static final int ROLE_EXPIRE_DAYS = 30;
    private static final int BATCH_EXPIRE_DAYS = 15;
    private static final int REQUEST_DAYS = 7;
    private static final int EXPIRING_WINDOW_DAYS = 60;
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    @Autowired MockMvc mvc;
    @Value("${melonhub.admin-username}") String bootstrap;
    @Autowired ObjectMapper mapper;
    @Autowired OrganizationUseCase orgs;
    @Autowired DomainRbacUseCase rbac;
    @Autowired AuthUseCase auth;

    private final Map<String, JsonNode> shapes = new TreeMap<>();
    private String bearer, apiKey;

    @DisplayName("R31 核心接口 JSON 响应结构与 api-shapes.json 契约一致")
    @Test void responseShapesMatchTheRecordedContract() throws Exception {
        String s = Credentials.token().substring(0, 10);
        String code1 = "cd1-" + s, code2 = "cd2-" + s, external = "ext-" + s, external2 = external + "-b", operator = "contract-" + s;
        long d1 = orgs.department(new SaveDepartmentCommand("Contract One " + s, code1), null, ACTOR);
        long d2 = orgs.department(new SaveDepartmentCommand("Contract Two " + s, code2), null, ACTOR);

        auth.createAccount(new CreateAccountCommand(operator, null, AdminPrincipal.SUPER_ADMIN, PASSWORD, true, null),
                auth.principal(bootstrap));
        JsonNode login = record("POST /api/auth/login", post("/api/auth/login"), Map.of("username", operator, "password", PASSWORD), false);
        bearer = "Bearer " + login.get("token").asText();
        record("GET /api/auth/me", get("/api/auth/me"));
        record("GET /api/health", get("/api/health"));

        JsonNode created = record("POST /api/admin/applications", post("/api/admin/applications"),
                new SaveApplicationCommand("Contract App " + s, "ca-" + s, "contract fixture", "owner"), true);
        long a1 = created.get("id").asLong();
        apiKey = created.get("apiKey").asText();
        String domain = "departmentId=" + d1 + "&applicationId=" + a1;

        long u1 = orgs.user(new SaveUserCommand("Contract User " + s, external, "u1@example.com", List.of(d1, d2)), null, ACTOR);
        long u2 = orgs.user(new SaveUserCommand("Contract Second " + s, external2, "", List.of(d1)), null, ACTOR);
        long p1 = rbac.permission(new SavePermissionCommand("Read", PERM_READ, "item", "read"), null, d1, a1, ACTOR);
        long p2 = rbac.permission(new SavePermissionCommand("Write", PERM_WRITE, "item", "write"), null, d1, a1, ACTOR);
        long r1 = rbac.role(new SaveRoleCommand("Reader " + s, "reader", "reads items"), null, d1, a1, ACTOR);
        long r2 = rbac.role(new SaveRoleCommand("Writer " + s, "writer", ""), null, d1, a1, ACTOR);
        rbac.rolePermissions(r1, d1, a1, List.of(p1), ACTOR);
        rbac.rolePermissions(r2, d1, a1, List.of(p1, p2), ACTOR);
        rbac.roleParents(r2, d1, a1, List.of(r1), ACTOR);
        JsonNode hook = record("POST /api/admin/applications/{id}/webhooks", post("/api/admin/applications/" + a1 + "/webhooks"),
                new SaveWebhookCommand("Contract hook", HOOK_URL, null, List.of("*"), true), true);
        rbac.userRoles(u1, d1, a1, List.of(r1, r2), ROLE_EXPIRE_DAYS, ACTOR);
        rbac.roleMembers(r1, d1, a1, List.of(u1, u2), ACTOR);

        // ---- organisation and applications ----
        record("GET /api/admin/departments", get("/api/admin/departments"), ids(d1, d2));
        record("GET /api/admin/applications", get("/api/admin/applications"), ids(a1));
        record("POST /api/admin/applications/{id}/keys", post("/api/admin/applications/" + a1 + "/keys"),
                new CreateApiKeyCommand("second", KEY_EXPIRE_DAYS), true);
        record("GET /api/admin/applications/{id}/keys", get("/api/admin/applications/" + a1 + "/keys"));
        record("GET /api/admin/users", get("/api/admin/users?keyword=" + s + "&page=1&size=20"));
        record("GET /api/admin/users/export", get("/api/admin/users/export?keyword=" + s));
        record("GET /api/admin/users/{id}/access", get("/api/admin/users/" + u1 + "/access"));
        record("POST /api/admin/users/import", post("/api/admin/users/import"), new UserImportCommand(List.of(
                new UserImportRowCommand("Imported " + s, "imp-" + s, "", List.of(code1)),
                new UserImportRowCommand("Broken " + s, "bad-" + s, "", List.of("no-such-department-" + s)))), true);

        // ---- one isolation domain ----
        record("GET /api/admin/roles", get("/api/admin/roles?" + domain));
        record("GET /api/admin/roles/{id}/members", get("/api/admin/roles/" + r1 + "/members?" + domain));
        record("GET /api/admin/permissions", get("/api/admin/permissions?" + domain));
        record("GET /api/admin/users/{id}/roles", get("/api/admin/users/" + u1 + "/roles?" + domain));
        record("GET /api/admin/assignments", get("/api/admin/assignments?" + domain + "&page=1&size=20"));
        record("POST /api/admin/assignments/batch", post("/api/admin/assignments/batch?" + domain),
                new BatchUserRolesCommand(List.of(u2), List.of(r2), BATCH_EXPIRE_DAYS), true);
        record("GET /api/admin/overview", get("/api/admin/overview?" + domain));
        record("POST /api/admin/simulate", post("/api/admin/simulate"),
                new SimulationCommand(u1, d1, a1, null, List.of(PERM_READ, PERM_WRITE, PERM_DELETE)), true);
        record("POST /api/admin/domains/copy", post("/api/admin/domains/copy"), new DomainCopyCommand(a1, d1, d2), true);
        record("GET /api/admin/manifest", get("/api/admin/manifest?applicationId=" + a1));
        record("POST /api/admin/manifest/import", post("/api/admin/manifest/import?applicationId=" + a1), new ManifestImportCommand(1, List.of(
                new ManifestDepartmentCommand(code2,
                        List.of(new ManifestPermissionCommand(PERM_EXPORT, "Export", "item", "export")),
                        List.of(new ManifestRoleCommand("exporter", "Exporter", "", true, List.of(PERM_EXPORT, PERM_READ)))))), true);
        record("GET /api/admin/permissions/who", get("/api/admin/permissions/who?applicationId=" + a1 + "&code=" + PERM_READ));
        record("GET /api/admin/assignments/expiring", get("/api/admin/assignments/expiring?days=" + EXPIRING_WINDOW_DAYS + "&applicationId=" + a1));

        // ---- business API ----
        record("GET /api/v1/departments", business(get("/api/v1/departments")), ids(d1, d2));
        record("GET /api/v1/departments/{dept}/users", business(get("/api/v1/departments/" + d1 + "/users")));
        record("GET /api/v1/departments/{dept}/roles", business(get("/api/v1/departments/" + d1 + "/roles")));
        record("GET /api/v1/departments/{dept}/permissions", business(get("/api/v1/departments/" + d1 + "/permissions")));
        record("GET /api/v1/departments/{dept}/users/{id}/roles", business(get("/api/v1/departments/" + d1 + "/users/" + u1 + "/roles")));
        JsonNode context = record("POST /api/v1/contexts", business(post("/api/v1/contexts")), new IssueContextCommand(external, d1, null), false);
        String token = context.get("contextToken").asText();
        record("GET /api/v1/contexts/effective", business(get("/api/v1/contexts/effective")).header("X-Context-Token", token));
        record("POST /api/v1/contexts/check", business(post("/api/v1/contexts/check")).header("X-Context-Token", token),
                new PermissionCheckDTO(List.of(PERM_READ, PERM_DELETE)), false);
        JsonNode submitted = record("POST /api/v1/access-requests", business(post("/api/v1/access-requests")),
                new SubmitAccessRequestCommand(external2, d1, r1, ACTOR, REQUEST_DAYS), false);
        record("GET /api/v1/access-requests", business(get("/api/v1/access-requests?externalId=" + external2)));
        record("GET /api/v1/users/access", business(get("/api/v1/users/access?externalId=" + external + "&departmentId=" + d1)));

        // ---- governance ----
        record("GET /api/admin/access-requests", get("/api/admin/access-requests?applicationId=" + a1));
        record("POST /api/admin/access-requests/{id}/approve", post("/api/admin/access-requests/" + submitted.get("id").asLong() + "/approve"),
                new ApproveRequestCommand(ROLE_EXPIRE_DAYS, "approved"), true);
        JsonNode review = record("POST /api/admin/reviews", post("/api/admin/reviews"),
                new CreateReviewCommand("Contract review", a1, d1, REQUEST_DAYS), true);
        long reviewId = review.get("id").asLong();
        record("GET /api/admin/reviews", get("/api/admin/reviews?applicationId=" + a1));
        record("GET /api/admin/reviews/{id}", get("/api/admin/reviews/" + reviewId));
        record("GET /api/admin/reviews/{id}/items", get("/api/admin/reviews/" + reviewId + "/items"));
        record("POST /api/admin/reviews/{id}/close", post("/api/admin/reviews/" + reviewId + "/close"), null, true);
        record("GET /api/admin/applications/{id}/webhooks", get("/api/admin/applications/" + a1 + "/webhooks"));
        record("GET /api/admin/webhooks/{id}/deliveries", get("/api/admin/webhooks/" + hook.get("id").asLong() + "/deliveries"));

        // ---- console, audit and accounts ----
        record("GET /api/admin/search", get("/api/admin/search?q=" + s));
        record("GET /api/admin/system/status", get("/api/admin/system/status"));
        record("GET /api/admin/audit", get("/api/admin/audit?applicationId=" + a1 + "&page=1&size=50"));
        record("GET /api/admin/audit/export", get("/api/admin/audit/export?applicationId=" + a1));
        record("GET /api/admin/accounts", get("/api/admin/accounts"), row -> operator.equals(row.path("username").asText()));

        compare();
    }

    // ---- requests ----
    private MockHttpServletRequestBuilder business(MockHttpServletRequestBuilder request) { return request.header("X-API-Key", apiKey); }
    private JsonNode record(String label, MockHttpServletRequestBuilder request) throws Exception { return record(label, request, null, true, null); }
    private JsonNode record(String label, MockHttpServletRequestBuilder request, Predicate<JsonNode> keep) throws Exception {
        return record(label, request, null, true, keep);
    }
    private JsonNode record(String label, MockHttpServletRequestBuilder request, Object body, boolean console) throws Exception {
        return record(label, request, body, console, null);
    }
    private JsonNode record(String label, MockHttpServletRequestBuilder request, Object body, boolean console, Predicate<JsonNode> keep) throws Exception {
        if (console && bearer != null) request.header("Authorization", bearer);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        String text = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode node = mapper.readTree(text);
        JsonNode kept = node;
        if (keep != null && node.isArray()) {
            var rows = NODES.arrayNode();
            node.forEach(row -> { if (keep.test(row)) rows.add(row); });
            kept = rows;
        }
        shapes.put(label, shape(kept));
        return node;
    }
    private static Predicate<JsonNode> ids(long... ids) {
        var wanted = new TreeSet<Long>();
        for (long id : ids) wanted.add(id);
        return row -> wanted.contains(row.path("id").asLong());
    }

    // ---- shapes ----
    static JsonNode shape(JsonNode node) {
        if (node == null || node.isMissingNode()) return TextNode.valueOf("absent");
        if (node.isNull()) return TextNode.valueOf("null");
        if (node.isBoolean()) return TextNode.valueOf("boolean");
        if (node.isIntegralNumber()) return TextNode.valueOf("integer");
        if (node.isNumber()) return TextNode.valueOf("decimal");
        if (node.isTextual()) {
            String text = node.asText();
            return TextNode.valueOf(DATE.matcher(text).matches() ? "datetime:" + text.replaceAll("\\d", "d") : "string");
        }
        if (node.isArray()) {
            var array = NODES.arrayNode();
            JsonNode merged = null;
            for (JsonNode item : node) merged = merged == null ? shape(item) : merge(merged, shape(item));
            if (merged != null) array.add(merged);
            return array;
        }
        var fields = new TreeMap<String, JsonNode>();
        node.fields().forEachRemaining(e -> {
            String key = NUMERIC_KEY.matcher(e.getKey()).matches() ? "<number>" : e.getKey();
            JsonNode value = shape(e.getValue());
            fields.merge(key, value, R31_ApiContractIntegrationTest::merge);
        });
        var object = NODES.objectNode();
        fields.forEach(object::set);
        return object;
    }

    static JsonNode merge(JsonNode a, JsonNode b) {
        if (a.equals(b)) return a;
        if (a.isObject() && b.isObject()) {
            var names = new TreeSet<String>();
            a.fieldNames().forEachRemaining(names::add);
            b.fieldNames().forEachRemaining(names::add);
            var object = NODES.objectNode();
            for (String name : names) {
                JsonNode left = a.has(name) ? a.get(name) : TextNode.valueOf("absent");
                JsonNode right = b.has(name) ? b.get(name) : TextNode.valueOf("absent");
                object.set(name, merge(left, right));
            }
            return object;
        }
        if (a.isArray() && b.isArray()) {
            if (a.isEmpty()) return b;
            if (b.isEmpty()) return a;
            return NODES.arrayNode().add(merge(a.get(0), b.get(0)));
        }
        if (a.isTextual() && b.isTextual()) {
            var kinds = new TreeSet<String>(List.of(a.asText().split("\\|")));
            kinds.addAll(List.of(b.asText().split("\\|")));
            return TextNode.valueOf(String.join("|", kinds));
        }
        // A container that is sometimes null or a scalar.
        var choices = new ArrayList<JsonNode>();
        for (JsonNode side : new JsonNode[] {a, b}) {
            if (side.has("oneOf")) side.get("oneOf").forEach(choices::add); else choices.add(side);
        }
        choices.sort((x, y) -> x.toString().compareTo(y.toString()));
        ObjectNode union = NODES.objectNode();
        ArrayNode list = union.putArray("oneOf");
        choices.stream().distinct().forEach(list::add);
        return union;
    }

    // ---- recording ----
    private void compare() throws Exception {
        ObjectNode actual = NODES.objectNode();
        shapes.forEach(actual::set);
        if (Boolean.getBoolean("contract.update") || !Files.exists(RECORDED)) {
            Files.createDirectories(RECORDED.getParent());
            Files.writeString(RECORDED, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(actual) + System.lineSeparator());
            return;
        }
        JsonNode expected = mapper.readTree(Files.readString(RECORDED));
        var problems = new ArrayList<String>();
        var labels = new TreeSet<String>();
        expected.fieldNames().forEachRemaining(labels::add);
        actual.fieldNames().forEachRemaining(labels::add);
        for (String label : labels) {
            JsonNode want = expected.get(label), got = actual.get(label);
            if (want == null) problems.add(label + ": not recorded");
            else if (got == null) problems.add(label + ": no longer checked");
            else differences(label, want, got, problems);
        }
        if (!problems.isEmpty())
            fail("API response shapes changed (re-record with -Dcontract.update=true if intended):\n  " + String.join("\n  ", problems));
    }

    private static void differences(String path, JsonNode want, JsonNode got, List<String> problems) {
        if (want.equals(got)) return;
        if (want.isObject() && got.isObject() && !want.has("oneOf") && !got.has("oneOf")) {
            var names = new TreeSet<String>();
            want.fieldNames().forEachRemaining(names::add);
            got.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (!got.has(name)) problems.add(path + "." + name + ": missing (was " + want.get(name) + ")");
                else if (!want.has(name)) problems.add(path + "." + name + ": new field " + got.get(name));
                else differences(path + "." + name, want.get(name), got.get(name), problems);
            }
            return;
        }
        if (want.isArray() && got.isArray() && !want.isEmpty() && !got.isEmpty()) {
            differences(path + "[]", want.get(0), got.get(0), problems);
            return;
        }
        problems.add(path + ": expected " + want + " but was " + got);
    }
}
