package com.melonhub.rbac.controller;

import com.melonhub.rbac.dto.Requests;
import com.melonhub.rbac.security.AdminAccess;
import com.melonhub.rbac.security.AdminPrincipal;
import com.melonhub.rbac.service.RbacService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

/**
 * Console API. Every handler resolves the operator itself, so a request that somehow skipped the filter still
 * fails closed, and application-scoped administrators only see or change their own applications.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final RbacService r;
    public AdminController(RbacService r) { this.r = r; }

    private static Collection<Long> visible(AdminPrincipal principal) { return principal.allApplications() ? null : principal.applicationIds(); }
    private static int page(int page) { return Math.max(1, page); }
    private static int size(int size) { return Math.min(200, Math.max(1, size)); }
    private static Map<String,Object> ok() { return Map.of("ok", true); }

    // ---- departments ----
    @GetMapping("/departments") Object departments(HttpServletRequest req) { AdminAccess.of(req); return r.departments(); }
    @PostMapping("/departments") Object department(@Valid @RequestBody Requests.Department b, HttpServletRequest req) {
        return Map.of("id", r.department(b, null, AdminAccess.platformWrite(req).actor()));
    }
    @PutMapping("/departments/{id}") Object department(@PathVariable long id, @Valid @RequestBody Requests.Department b, HttpServletRequest req) {
        return Map.of("id", r.department(b, id, AdminAccess.platformWrite(req).actor()));
    }
    @DeleteMapping("/departments/{id}") Object deleteDepartment(@PathVariable long id, HttpServletRequest req) {
        r.deleteDepartment(id, AdminAccess.platformWrite(req).actor()); return ok();
    }
    @PostMapping("/departments/{id}/members") Object addDepartmentMembers(@PathVariable long id,
            @Valid @RequestBody Requests.Ids b, HttpServletRequest req) {
        return r.departmentMembers(id, b.ids(), true, AdminAccess.platformWrite(req).actor());
    }
    @DeleteMapping("/departments/{id}/members") Object removeDepartmentMembers(@PathVariable long id,
            @Valid @RequestBody Requests.Ids b, HttpServletRequest req) {
        return r.departmentMembers(id, b.ids(), false, AdminAccess.platformWrite(req).actor());
    }

    // ---- applications and API keys ----
    @GetMapping("/applications") Object applications(HttpServletRequest req) { return r.applications(visible(AdminAccess.of(req))); }
    @PostMapping("/applications") Object application(@Valid @RequestBody Requests.Application b, HttpServletRequest req) {
        return r.application(b, AdminAccess.platformWrite(req).actor());
    }
    @PutMapping("/applications/{id}") Object application(@PathVariable long id, @Valid @RequestBody Requests.Application b, HttpServletRequest req) {
        return Map.of("id", r.updateApplication(id, b, AdminAccess.platformWrite(req).actor()));
    }
    @PatchMapping("/applications/{id}/status") Object applicationStatus(@PathVariable long id, @Valid @RequestBody Requests.Enabled b, HttpServletRequest req) {
        r.applicationStatus(id, b.enabled(), AdminAccess.platformWrite(req).actor()); return ok();
    }
    @DeleteMapping("/applications/{id}") Object deleteApplication(@PathVariable long id, HttpServletRequest req) {
        r.deleteApplication(id, AdminAccess.platformWrite(req).actor()); return ok();
    }
    @GetMapping("/applications/{id}/keys") Object keys(@PathVariable long id, HttpServletRequest req) { AdminAccess.readApplication(req, id); return r.keys(id); }
    @PostMapping("/applications/{id}/keys") Object createKey(@PathVariable long id, @Valid @RequestBody Requests.ApiKey b, HttpServletRequest req) {
        return r.createKey(id, b, AdminAccess.writeApplication(req, id).actor());
    }
    @DeleteMapping("/applications/{id}/keys/{keyId}") Object revokeKey(@PathVariable long id, @PathVariable long keyId, HttpServletRequest req) {
        r.revokeKey(id, keyId, AdminAccess.writeApplication(req, id).actor()); return ok();
    }
    @PostMapping("/applications/{id}/rotate-key") Object rotate(@PathVariable long id, HttpServletRequest req) {
        return Map.of("apiKey", r.rotateKey(id, AdminAccess.writeApplication(req, id).actor()));
    }

    // ---- users ----
    @GetMapping("/users") Object users(@RequestParam(required=false) String keyword, @RequestParam(required=false) Long departmentId,
                                       @RequestParam(required=false) Boolean enabled, @RequestParam(defaultValue="1") int page,
                                       @RequestParam(defaultValue="20") int size, HttpServletRequest req) {
        AdminAccess.of(req);
        return r.users(keyword, departmentId, enabled, page(page), size(size));
    }
    @GetMapping("/users/export") Object exportUsers(@RequestParam(required=false) String keyword,
                                                     @RequestParam(required=false) Long departmentId,
                                                     @RequestParam(required=false) Boolean enabled, HttpServletRequest req) {
        AdminAccess.of(req);
        return r.users(keyword, departmentId, enabled, 1, Integer.MAX_VALUE);
    }
    @PostMapping("/users") Object user(@Valid @RequestBody Requests.User b, HttpServletRequest req) {
        return Map.of("id", r.user(b, null, AdminAccess.platformWrite(req).actor()));
    }
    @PutMapping("/users/{id}") Object user(@PathVariable long id, @Valid @RequestBody Requests.User b, HttpServletRequest req) {
        return Map.of("id", r.user(b, id, AdminAccess.platformWrite(req).actor()));
    }
    @PatchMapping("/users/{id}/status") Object userStatus(@PathVariable long id, @Valid @RequestBody Requests.Enabled b, HttpServletRequest req) {
        r.userStatus(id, b.enabled(), AdminAccess.platformWrite(req).actor()); return ok();
    }
    @DeleteMapping("/users/{id}") Object deleteUser(@PathVariable long id, HttpServletRequest req) {
        r.deleteUser(id, AdminAccess.platformWrite(req).actor()); return ok();
    }
    @GetMapping("/users/{id}/access") Object access(@PathVariable long id, HttpServletRequest req) { return r.userAccess(id, visible(AdminAccess.of(req))); }
    @DeleteMapping("/users/{id}/contexts") Object revokeContexts(@PathVariable long id, HttpServletRequest req) {
        return Map.of("revoked", r.revokeUserContexts(id, AdminAccess.platformWrite(req).actor()));
    }

    // ---- one isolation domain (application) ----
    @GetMapping("/roles") Object roles(@RequestParam long applicationId, HttpServletRequest req) {
        AdminAccess.readApplication(req, applicationId); return r.roles(applicationId);
    }
    @PostMapping("/roles") Object role(@RequestParam long applicationId, @Valid @RequestBody Requests.Role b, HttpServletRequest req) {
        return Map.of("id", r.role(b, null, applicationId, AdminAccess.writeApplication(req, applicationId).actor()));
    }
    @PutMapping("/roles/{id}") Object role(@PathVariable long id, @RequestParam long applicationId,
                                           @Valid @RequestBody Requests.Role b, HttpServletRequest req) {
        return Map.of("id", r.role(b, id, applicationId, AdminAccess.writeApplication(req, applicationId).actor()));
    }
    @PatchMapping("/roles/{id}/status") Object roleStatus(@PathVariable long id, @RequestParam long applicationId,
                                                          @Valid @RequestBody Requests.Enabled b, HttpServletRequest req) {
        r.roleStatus(id, applicationId, b.enabled(), AdminAccess.writeApplication(req, applicationId).actor()); return ok();
    }
    @DeleteMapping("/roles/{id}") Object deleteRole(@PathVariable long id, @RequestParam long applicationId, HttpServletRequest req) {
        r.deleteRole(id, applicationId, AdminAccess.writeApplication(req, applicationId).actor()); return ok();
    }
    @PutMapping("/roles/{id}/permissions") Object rolePermissions(@PathVariable long id, @RequestParam long applicationId,
                                                                  @Valid @RequestBody Requests.Ids b, HttpServletRequest req) {
        r.rolePermissions(id, applicationId, b.ids(), AdminAccess.writeApplication(req, applicationId).actor()); return ok();
    }
    @GetMapping("/roles/{id}/members") Object roleMembers(@PathVariable long id, @RequestParam long applicationId, HttpServletRequest req) {
        AdminAccess.readApplication(req, applicationId); return r.roleMembers(id, applicationId);
    }
    @PutMapping("/roles/{id}/members") Object roleMembers(@PathVariable long id, @RequestParam long applicationId,
                                                          @Valid @RequestBody Requests.Ids b, HttpServletRequest req) {
        r.roleMembers(id, applicationId, b.ids(), AdminAccess.writeApplication(req, applicationId).actor()); return ok();
    }
    @GetMapping("/permissions") Object permissions(@RequestParam long applicationId, HttpServletRequest req) {
        AdminAccess.readApplication(req, applicationId); return r.permissions(applicationId);
    }
    @PostMapping("/permissions") Object permission(@RequestParam long applicationId,
                                                   @Valid @RequestBody Requests.Permission b, HttpServletRequest req) {
        return Map.of("id", r.permission(b, null, applicationId, AdminAccess.writeApplication(req, applicationId).actor()));
    }
    @PutMapping("/permissions/{id}") Object permission(@PathVariable long id, @RequestParam long applicationId,
                                                       @Valid @RequestBody Requests.Permission b, HttpServletRequest req) {
        return Map.of("id", r.permission(b, id, applicationId, AdminAccess.writeApplication(req, applicationId).actor()));
    }
    @DeleteMapping("/permissions/{id}") Object deletePermission(@PathVariable long id, @RequestParam long applicationId, HttpServletRequest req) {
        r.deletePermission(id, applicationId, AdminAccess.writeApplication(req, applicationId).actor()); return ok();
    }
    @GetMapping("/users/{id}/roles") Object userRoles(@PathVariable long id, @RequestParam long applicationId, HttpServletRequest req) {
        AdminAccess.readApplication(req, applicationId); return r.assignments(id, applicationId);
    }
    @PutMapping("/users/{id}/roles") Object userRoles(@PathVariable long id, @RequestParam long applicationId,
                                                      @Valid @RequestBody Requests.UserRoles b, HttpServletRequest req) {
        r.userRoles(id, applicationId, b.ids(), b.expiresInDays(), b.expiries(), AdminAccess.writeApplication(req, applicationId).actor()); return ok();
    }
    @GetMapping("/assignments") Object assignments(@RequestParam(required=false) Long departmentId, @RequestParam long applicationId, @RequestParam(required=false) String keyword,
                                                   @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size, HttpServletRequest req) {
        AdminAccess.readApplication(req, applicationId);
        return r.domainAssignments(departmentId, applicationId, keyword, page(page), size(size));
    }
    @PostMapping("/assignments/batch") Object addUserRoles(@RequestParam long applicationId,
                                                           @Valid @RequestBody Requests.BatchUserRoles b, HttpServletRequest req) {
        return r.addUserRoles(applicationId, b, AdminAccess.writeApplication(req, applicationId).actor());
    }
    @PostMapping("/simulate") Object simulate(@Valid @RequestBody Requests.Simulation b, HttpServletRequest req) {
        AdminAccess.readApplication(req, b.applicationId()); return r.simulate(b);
    }
    @GetMapping("/overview") Object overview(@RequestParam long applicationId, HttpServletRequest req) {
        AdminAccess.readApplication(req, applicationId); return r.overview(applicationId);
    }

    // ---- audit ----
    @GetMapping("/audit/export") Object exportAudit(@RequestParam(required=false) Long departmentId, @RequestParam(required=false) Long applicationId,
                                                    @RequestParam(required=false) String actor, @RequestParam(required=false) String action,
                                                    @RequestParam(required=false) String keyword,
                                                    @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
                                                    @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
                                                    HttpServletRequest req) {
        return r.events(departmentId, applicationId, visible(AdminAccess.of(req)), actor, action, keyword, from, to, 1, 5000);
    }
    @GetMapping("/audit") Object audit(@RequestParam(required=false) Long departmentId, @RequestParam(required=false) Long applicationId,
                                       @RequestParam(required=false) String actor, @RequestParam(required=false) String action,
                                       @RequestParam(required=false) String keyword,
                                       @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
                                       @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size, HttpServletRequest req) {
        var principal = AdminAccess.of(req);
        return r.events(departmentId, applicationId, visible(principal), actor, action, keyword, from, to, page(page), size(size));
    }
}
