package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.Role;
import com.melonhub.rbac.infrastructure.persistence.entity.RoleInheritance;
import com.melonhub.rbac.infrastructure.persistence.entity.RoleInheritanceId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface RoleInheritanceRepository extends JpaRepository<RoleInheritance, RoleInheritanceId> {
    interface Edge {
        long getChildRoleId();
        long getParentRoleId();
    }

    @Query("""
            select ri.id.childRoleId as childRoleId, ri.id.parentRoleId as parentRoleId
            from RoleInheritance ri
            where ri.departmentId = :departmentId and ri.applicationId = :applicationId
            order by ri.id.childRoleId, ri.id.parentRoleId""")
    List<Edge> findEdgesByDomain(long departmentId, long applicationId);

    @Query("""
            select ri.id.childRoleId as childRoleId, ri.id.parentRoleId as parentRoleId
            from RoleInheritance ri join ri.childRole c join ri.parentRole p
            where ri.departmentId = :departmentId and ri.applicationId = :applicationId
              and c.enabled = true and p.enabled = true
            order by ri.id.childRoleId, ri.id.parentRoleId""")
    List<Edge> findActiveEdgesByDomain(long departmentId, long applicationId);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RoleInheritance ri where ri.id.childRoleId = :childRoleId and ri.id.parentRoleId = :parentRoleId")
    int deleteByEdge(long childRoleId, long parentRoleId);

    /** 仅按精确联合主键删旧插新，避免范围 DELETE 触发间隙锁/supremum 锁。 */
    @Transactional
    default void replaceParents(long childRoleId, long departmentId, long applicationId,
                                Collection<Long> existingParentIds, Collection<Long> parentIds,
                                LongFunction<Role> roleRef) {
        var existing = new LinkedHashSet<>(existingParentIds);
        var wanted = new LinkedHashSet<>(parentIds);
        for (long oldParent : existing) {
            if (!wanted.contains(oldParent)) {
                deleteByEdge(childRoleId, oldParent);
            }
        }
        var toInsert = new ArrayList<RoleInheritance>();
        Role child = null;
        for (long parentId : wanted) {
            if (existing.contains(parentId)) continue;
            if (child == null) child = roleRef.apply(childRoleId);
            toInsert.add(new RoleInheritance()
                    .setId(new RoleInheritanceId(childRoleId, parentId))
                    .setChildRole(child)
                    .setParentRole(roleRef.apply(parentId))
                    .setDepartmentId(departmentId)
                    .setApplicationId(applicationId));
        }
        if (!toInsert.isEmpty()) {
            saveAllAndFlush(toInsert);
        }
    }

    /** 沿给定继承边集合从根角色集合展开全部可达父角色 ID。 */
    static List<Long> expandEdges(Collection<Long> roots, List<Edge> edges) {
        if (roots == null || roots.isEmpty()) return List.of();
        var parents = new LinkedHashMap<Long, List<Long>>();
        for (var edge : edges) {
            parents.computeIfAbsent(edge.getChildRoleId(), k -> new ArrayList<>()).add(edge.getParentRoleId());
        }
        var result = new LinkedHashSet<Long>();
        var pending = new ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            long roleId = pending.removeFirst();
            if (!result.add(roleId)) continue;
            pending.addAll(parents.getOrDefault(roleId, List.of()));
        }
        return new ArrayList<>(result);
    }

    /** 返回当前有效角色集合及隔离域内仅沿启用角色传递继承的父角色 ID（用于实际鉴权与模拟）。 */
    default List<Long> expandActiveRoleIds(Collection<Long> roots, long departmentId, long applicationId) {
        if (roots == null || roots.isEmpty()) return List.of();
        return expandEdges(roots, findActiveEdgesByDomain(departmentId, applicationId));
    }

    /**
     * 按直接激活角色分别沿启用继承边展开闭包，返回「可达角色 ID -> 使其生效的直接角色 ID 列表」映射，
     * 供权限诊断将继承权限准确归因回用户直接持有的激活角色。
     */
    default Map<Long, List<Long>> mapExpandedToDirectActiveRoles(Collection<Long> directRoles, long departmentId, long applicationId) {
        if (directRoles == null || directRoles.isEmpty()) return Map.of();
        var parents = new LinkedHashMap<Long, List<Long>>();
        for (var edge : findActiveEdgesByDomain(departmentId, applicationId)) {
            parents.computeIfAbsent(edge.getChildRoleId(), k -> new ArrayList<>()).add(edge.getParentRoleId());
        }
        var sources = new LinkedHashMap<Long, List<Long>>();
        for (long direct : directRoles) {
            var visited = new LinkedHashSet<Long>();
            var pending = new ArrayDeque<Long>();
            pending.add(direct);
            while (!pending.isEmpty()) {
                long cur = pending.removeFirst();
                if (!visited.add(cur)) continue;
                sources.computeIfAbsent(cur, k -> new ArrayList<>()).add(direct);
                pending.addAll(parents.getOrDefault(cur, List.of()));
            }
        }
        return sources;
    }
}
