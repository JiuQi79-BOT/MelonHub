package com.melonhub.rbac.requirements.b_rbac_domain;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.melonhub.rbac.dto.Requests;
import com.melonhub.rbac.exception.ApiException;
import com.melonhub.rbac.requirements.support.RbacIntegrationSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;

/** 对应需求：R5、R6、R7、R8（见 doc/requirements.md）。 */
class R05_R08_DomainRbacIntegrationTest extends RbacIntegrationSupport {

    @DisplayName("R34 RBAC1 子角色继承父角色权限并拒绝循环和跨域关系")
    @Test void roleHierarchyInheritsPermissionsAndRejectsInvalidEdges() {
        r.roleParents(r2, d1, a1, List.of(r1), "test");
        r.userRoles(u1, d1, a1, List.of(r2), "test");
        var token = issue(a1, d1);
        assertThat(c.effective(a1, token).get("permissions")).isEqualTo(List.of("item:read", "item:write"));
        assertThat(r.roles(d1, a1).stream().filter(row -> ((Number) row.get("id")).longValue() == r2)
                .findFirst().orElseThrow().get("parentRoleIds")).isEqualTo(List.of(r1));
        assertThatThrownBy(() -> r.roleParents(r1, d1, a1, List.of(r2), "test")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> r.roleParents(r2, d1, a1, List.of(rOther), "test")).isInstanceOf(ApiException.class);
    }

    @DisplayName("R5 跨域角色权限关联被应用层原子拒绝")
    @Test void crossDomainRolePermissionAssignmentsAreRejectedAtomically() {
        assertThatThrownBy(() -> r.rolePermissions(r1, d1, a1, List.of(p1, pOther), "test")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> r.rolePermissions(r1, d1, a1, List.of(pOtherApp), "test")).isInstanceOf(ApiException.class);
        assertThat(r.roles(d1, a1).get(0).get("permissionIds")).isEqualTo(List.of(p1));
    }

    @DisplayName("R5 数据库复合外键物理拒绝跨域关联")
    @Test void databaseForeignKeysAlsoRejectCrossDomainRelationships() {
        assertThatThrownBy(() -> db.update("INSERT INTO role_permissions VALUES (?,?,?,?)", r1, pOther, d1, a1)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("INSERT INTO user_roles(user_id,role_id,department_id,application_id) VALUES (?,?,?,?)", u2, rOther, d1, a1)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @DisplayName("R6 移除角色权限在既有上下文中立即生效")
    @Test void permissionRevocationIsVisibleInExistingContext() {
        String token = issue(a1, d1);
        r.rolePermissions(r2, d1, a1, List.of(), "test");
        assertThat(c.check(a1, token, List.of("item:write")).get("allowed")).isEqualTo(false);
        assertThat(c.check(a1, token, List.of("item:read")).get("allowed")).isEqualTo(true);
    }

    @DisplayName("R7 移除用户角色使当前域上下文失效")
    @Test void removingUserRoleInvalidatesContext() {
        String token = issue(a1, d1);
        r.userRoles(u1, d1, a1, List.of(r1), "test");
        assertThatThrownBy(() -> c.effective(a1, token)).isInstanceOf(ApiException.class);
        assertThat(c.effective(a1, issue(a1, d1)).get("permissions")).isEqualTo(List.of("item:read"));
    }

    @DisplayName("R7 单软件重分配角色不影响其他软件上下文")
    @Test void reassigningRolesInOneApplicationKeepsContextsOfOtherApplications() {
        String one = issue(a1, d1), two = issue(a2, d1);
        r.userRoles(u1, d1, a1, List.of(r1), "test");
        assertThatThrownBy(() -> c.effective(a1, one)).isInstanceOf(ApiException.class);
        assertThat(c.effective(a2, two).get("permissions")).isEqualTo(List.of("item:read"));
    }

    @DisplayName("R6 支持从角色侧反向维护成员列表")
    @Test void roleMembersCanBeManagedFromTheRoleSide() {
        r.roleMembers(r1, d1, a1, List.of(u1, u2), "test");
        assertThat(r.roleMembers(r1, d1, a1)).extracting(m -> ((Number) m.get("id")).longValue()).containsExactly(u1, u2);
        String token = (String) c.issue(a1, new Requests.Context(external + "-other", d1, null), "test").get("contextToken");
        assertThat(c.effective(a1, token).get("permissions")).isEqualTo(List.of("item:read"));
        long outsider = r.user(new Requests.User("Outsider", external + "-out", "", List.of(d2)), null, "test");
        assertThatThrownBy(() -> r.roleMembers(r1, d1, a1, List.of(outsider), "test")).isInstanceOf(ApiException.class);
        r.roleMembers(r1, d1, a1, List.of(u1), "test");
        assertThatThrownBy(() -> c.effective(a1, token)).isInstanceOf(ApiException.class);
    }

    @DisplayName("R8 跨部门复制跳过已存在编码并复制角色权限绑定")
    @Test void domainCopySkipsExistingCodesAndCopiesGrants() {
        var result = r.copyDomain(new Requests.DomainCopy(a1, d1, d2), "test");
        assertThat(result).containsEntry("permissionsCreated", 1).containsEntry("permissionsSkipped", 1).containsEntry("rolesCreated", 1).containsEntry("rolesSkipped", 1);
        var writer = r.roles(d2, a1).stream().filter(x -> "writer".equals(x.get("code"))).findFirst().orElseThrow();
        var write = r.permissions(d2, a1).stream().filter(x -> "item:write".equals(x.get("code"))).findFirst().orElseThrow();
        assertThat(writer.get("permissionIds")).isEqualTo(List.of(((Number) write.get("id")).longValue()));
        assertThatThrownBy(() -> r.copyDomain(new Requests.DomainCopy(a1, d1, d1), "test")).isInstanceOf(ApiException.class);
    }

    @DisplayName("R5 业务接口不能修改或删除异域角色")
    @Test void businessCrudCannotEditOrDeleteRolesInAnotherDomain() throws Exception {
        mvc.perform(put("/api/v1/departments/" + d1 + "/roles/" + rOtherApp).header("X-API-Key", key1)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Malicious\",\"code\":\"bad\"}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/departments/" + d1 + "/roles/" + rOtherApp).header("X-API-Key", key1)).andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/departments/" + d1 + "/roles/" + rOtherApp + "/status").header("X-API-Key", key1)
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}")).andExpect(status().isForbidden());
        assertThat(r.roles(d1, a2).get(0).get("name")).isEqualTo("Reader");
        assertThat(r.roles(d1, a2).get(0).get("enabled")).isEqualTo(true);
    }
}
