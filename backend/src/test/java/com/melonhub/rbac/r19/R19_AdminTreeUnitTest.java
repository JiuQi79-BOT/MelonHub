package com.melonhub.rbac.r19;

import static org.assertj.core.api.Assertions.assertThat;

import com.melonhub.rbac.service.ddd.domain.model.AdminHierarchy;
import com.melonhub.rbac.service.ddd.domain.value.AdminScope;
import com.melonhub.rbac.service.structured.admin.StructuredAdminHierarchyProcedures;
import com.melonhub.rbac.service.structured.admin.StructuredAdminHierarchyProcedures.AccountNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 对应需求：R19（见 doc/requirements.md）。同时校验 DDD 聚合根 AdminHierarchy 与结构化过程集 StructuredAdminHierarchyProcedures 的一致性。 */
class R19_AdminTreeUnitTest {

    // =========================================================================
    // 统一测试参数区（管理员角色功能集合、层级类型常量与预期违规文案前缀）
    // =========================================================================
    private static final String KIND_MANAGER = "MANAGER";
    private static final String KIND_DEPARTMENT = "DEPARTMENT";
    private static final String ROLE_SUPER = "SUPER_ADMIN";
    private static final String ROLE_MANAGER = "MANAGER_ROLE";
    private static final String ROLE_DEPT = "DEPT_ROLE";
    private static final Map<String, Set<String>> ROLES = Map.of(
            ROLE_SUPER, Set.of("org.view", "domain.view", "domain.assign", "account.manage", "role.manage"),
            ROLE_MANAGER, Set.of("org.view", "domain.view", "domain.assign", "account.manage"),
            ROLE_DEPT, Set.of("org.view", "domain.view", "domain.assign"));
    private static final long[][] MANAGE_PAIRS = { { 1, 4 }, { 2, 3 }, { 2, 4 }, { 2, 1 }, { 3, 2 }, { 1, 1 } };

    private static AccountNode manager(long id, String name, Long parentId, String role, Set<Long> departments) {
        return new AccountNode(id, name, parentId, KIND_MANAGER, role, true, departments == null, departments == null ? Set.of() : departments, true, Set.of());
    }

    private static AccountNode department(long id, String name, Long parentId, String role, Set<Long> departments) {
        return new AccountNode(id, name, parentId, KIND_DEPARTMENT, role, true, false, departments, true, Set.of());
    }

    private static AdminHierarchy toHierarchy(List<AccountNode> nodes) {
        var domainNodes = nodes.stream()
                .map(n -> new AdminHierarchy.AdminNode(n.id(), n.username(), n.parentId(), n.kind(), n.role(), n.enabled(),
                        new AdminScope(n.allDepartments(), n.departments(), n.allApplications(), n.applications())))
                .toList();
        return new AdminHierarchy(domainNodes, ROLES);
    }

    @DisplayName("R19 顶级管理员管全部账号且普通管理员仅管子树（结构化与 DDD 双策略一致）")
    @Test void rootsManageEveryoneAndManagersTheirSubtree() {
        var nodes = List.of(
                manager(1L, "root", null, ROLE_SUPER, null),
                manager(2L, "east", 1L, ROLE_MANAGER, Set.of(1L, 2L)),
                department(3L, "sales", 2L, ROLE_DEPT, Set.of(1L)),
                manager(4L, "west", 1L, ROLE_MANAGER, Set.of(3L)));
        var tree = toHierarchy(nodes);
        assertThat(tree.violations()).isEmpty();
        assertThat(StructuredAdminHierarchyProcedures.findViolations(nodes, ROLES)).isEmpty();
        assertThat(tree.descendants(1L)).containsExactlyInAnyOrder(2L, 3L, 4L);
        assertThat(StructuredAdminHierarchyProcedures.descendantIds(nodes, 1L)).containsExactlyInAnyOrder(2L, 3L, 4L);
        for (long[] pair : MANAGE_PAIRS) {
            assertThat(StructuredAdminHierarchyProcedures.manages(nodes, pair[0], pair[1]))
                    .isEqualTo(tree.manages(pair[0], pair[1]));
        }
        assertThat(tree.manages(1L, 4L)).isTrue();
        assertThat(tree.manages(2L, 3L)).isTrue();
        assertThat(tree.manages(2L, 4L)).isFalse();
        assertThat(tree.manages(2L, 1L)).isFalse();
        assertThat(tree.manages(3L, 2L)).isFalse();
        assertThat(tree.manages(1L, 1L)).isFalse();
    }

    @DisplayName("R19 下级管理员的功能、部门与软件范围不得超出上级（结构化与 DDD 双策略一致）")
    @Test void aChildNeverExceedsItsParent() {
        var nodes = List.of(
                manager(1L, "root", null, ROLE_SUPER, null),
                manager(2L, "east", 1L, ROLE_MANAGER, Set.of(1L, 2L)),
                department(5L, "wide", 2L, ROLE_DEPT, Set.of(1L, 3L)),
                manager(6L, "everywhere", 2L, ROLE_DEPT, null),
                manager(7L, "stronger", 2L, ROLE_SUPER, Set.of(1L)));
        var dddViolations = toHierarchy(nodes).violations();
        var structuredViolations = StructuredAdminHierarchyProcedures.findViolations(nodes, ROLES);
        assertThat(dddViolations).isEqualTo(structuredViolations).hasSize(3)
                .anyMatch(p -> p.startsWith("wide：部门范围超出上级"))
                .anyMatch(p -> p.startsWith("everywhere：部门范围超出上级"))
                .anyMatch(p -> p.startsWith("stronger：角色功能超出上级"));
    }

    @DisplayName("R19 部门管理员为限定部门的叶子节点且无账号角色管理功能（结构化与 DDD 双策略一致）")
    @Test void departmentAdministratorsAreLimitedLeavesWithoutAccountManagement() {
        var nodes = List.of(
                manager(1L, "root", null, ROLE_SUPER, null),
                department(8L, "unlimited", 1L, ROLE_DEPT, Set.of()),
                department(9L, "delegating", 1L, ROLE_MANAGER, Set.of(1L)),
                department(10L, "parent", 1L, ROLE_DEPT, Set.of(1L)),
                department(11L, "child", 10L, ROLE_DEPT, Set.of(1L)));
        var dddViolations = toHierarchy(nodes).violations();
        var structuredViolations = StructuredAdminHierarchyProcedures.findViolations(nodes, ROLES);
        assertThat(dddViolations).isEqualTo(structuredViolations)
                .anyMatch(p -> p.startsWith("unlimited：部门管理员必须指定所管理的部门"))
                .anyMatch(p -> p.startsWith("delegating：部门管理员不能拥有管理管理员账号或角色的功能"))
                .anyMatch(p -> p.startsWith("parent：部门管理员不能有下级管理员"));
    }

    @DisplayName("R19 管理员上下级链不得成环且须终止于根节点（结构化与 DDD 双策略一致）")
    @Test void parentChainsMustEndAtARoot() {
        var nodes = List.of(
                manager(12L, "a", 13L, ROLE_DEPT, null),
                manager(13L, "b", 12L, ROLE_DEPT, null),
                manager(14L, "orphan", 15L, ROLE_DEPT, null));
        var dddViolations = toHierarchy(nodes).violations();
        var structuredViolations = StructuredAdminHierarchyProcedures.findViolations(nodes, ROLES);
        assertThat(dddViolations).isEqualTo(structuredViolations)
                .contains("a：上级关系形成循环", "b：上级关系形成循环", "orphan：上级管理员不存在");
    }
}
