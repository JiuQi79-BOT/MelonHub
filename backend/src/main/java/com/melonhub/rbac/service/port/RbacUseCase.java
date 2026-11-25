package com.melonhub.rbac.service.port;

import com.melonhub.rbac.dao.Visibility;
import com.melonhub.rbac.dto.Requests;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 核心 RBAC 授权管理用例端口，由结构化实现和 DDD 实现共同履行。
 */
public interface RbacUseCase {
    ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    static Map<String, Object> page(List<Map<String, Object>> items, long total, int number, int size) {
        var result = new LinkedHashMap<String, Object>();
        result.put("items", items);
        result.put("total", total);
        result.put("page", number);
        result.put("size", size);
        return result;
    }

    Map<String, Object> health();

    default void audit(String actor, String action, String target, Long department, Long app) {
        audit(actor, action, target, department, app, "");
    }

    void audit(String actor, String action, String target, Long department, Long app, String detail);

    void domain(long dept, long app);

    void scoped(String table, long id, long dept, long app);

    List<Map<String, Object>> departments(Collection<Long> visible);

    long department(Requests.Department body, Long id, String actor);

    void deleteDepartment(long id, String actor);

    List<Map<String, Object>> applications(Collection<Long> visible);

    Map<String, Object> application(Requests.Application body, String actor);

    long updateApplication(long id, Requests.Application body, String actor);

    void applicationStatus(long id, boolean enabled, String actor);

    void deleteApplication(long id, String actor);

    List<Map<String, Object>> keys(long app);

    Map<String, Object> createKey(long app, Requests.ApiKey body, String actor);

    void revokeKey(long app, long key, String actor);

    String rotateKey(long app, String actor);

    Map<String, Object> users(String keyword, Long department, Boolean enabled, Collection<Long> visibleDepartments, int page, int size);

    List<Map<String, Object>> departmentUsers(long dept);

    default long user(Requests.User body, Long id, String actor) {
        return user(body, id, actor, null);
    }

    long user(Requests.User body, Long id, String actor, Collection<Long> scope);

    default void userStatus(long id, boolean enabled, String actor) {
        userStatus(id, enabled, actor, null);
    }

    void userStatus(long id, boolean enabled, String actor, Collection<Long> scope);

    void deleteUser(long id, String actor, Collection<Long> scope);

    String lockOwnedUser(long id, Collection<Long> scope);

    Map<String, Object> userAccess(long id, Visibility visible);

    int revokeUserContexts(long id, String actor, Collection<Long> scope);

    List<Map<String, Object>> roles(long dept, long app);

    long role(Requests.Role body, Long id, long dept, long app, String actor);

    void roleStatus(long id, long dept, long app, boolean enabled, String actor);

    void deleteRole(long id, long dept, long app, String actor);

    List<Map<String, Object>> permissions(long dept, long app);

    long permission(Requests.Permission body, Long id, long dept, long app, String actor);

    void deletePermission(long id, long dept, long app, String actor);

    void rolePermissions(long role, long dept, long app, List<Long> permissions, String actor);

    void roleParents(long role, long dept, long app, List<Long> parents, String actor);

    List<Map<String, Object>> roleMembers(long role, long dept, long app);

    void roleMembers(long role, long dept, long app, List<Long> users, String actor);

    Map<String, Object> assignments(long user, long dept, long app);

    Map<String, Object> domainAssignments(long dept, long app, String keyword, int page, int size);

    Map<String, Object> addUserRoles(long dept, long app, Requests.BatchUserRoles body, String actor);

    default void userRoles(long user, long dept, long app, List<Long> roles, String actor) {
        userRoles(user, dept, app, roles, null, null, actor);
    }

    default void userRoles(long user, long dept, long app, List<Long> roles, Integer expiresInDays, String actor) {
        userRoles(user, dept, app, roles, expiresInDays, null, actor);
    }

    void userRoles(long user, long dept, long app, List<Long> roles, Integer expiresInDays,
                   List<Requests.RoleExpiry> expiries, String actor);

    void applicationRateLimit(long id, Integer perSecond, String actor);

    Map<String, Object> copyDomain(Requests.DomainCopy body, String actor);

    Map<String, Object> simulate(Requests.Simulation body);

    Map<String, Object> events(Long dept, Long app, Visibility visible, String actor, String action, String keyword,
                               LocalDate from, LocalDate to, int page, int size);

    Map<String, Object> overview(long dept, long app);
}
