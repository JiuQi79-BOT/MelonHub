package com.melonhub.rbac.controller;

import com.melonhub.rbac.dto.Requests;
import com.melonhub.rbac.security.ApplicationCaller;
import com.melonhub.rbac.service.ContextService;
import com.melonhub.rbac.service.GovernanceService;
import com.melonhub.rbac.service.RbacService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** API for business systems. The API key fixes the application, so every call stays inside that application's domains. */
@RestController
@RequestMapping("/api/v1")
public class BusinessController {
    private final RbacService r;
    private final ContextService contexts;
    private final GovernanceService governance;
    public BusinessController(RbacService r, ContextService contexts, GovernanceService governance) {
        this.r = r; this.contexts = contexts; this.governance = governance;
    }

    private static long app(HttpServletRequest q) { return ApplicationCaller.of(q).applicationId(); }
    private static String actor(HttpServletRequest q) { return ApplicationCaller.of(q).actor(); }
    private static Map<String,Object> ok() { return Map.of("ok", true); }

    @GetMapping("/departments") Object departments(HttpServletRequest q) { app(q); return r.departments(); }
    @GetMapping("/departments/{dept}/users") Object users(@PathVariable long dept, HttpServletRequest q) { app(q); return r.departmentUsers(dept); }
    @GetMapping("/roles") Object roles(HttpServletRequest q) { return r.roles(app(q)); }
    @PostMapping("/roles") Object role(@Valid @RequestBody Requests.Role b, HttpServletRequest q) {
        return Map.of("id", r.role(b, null, app(q), actor(q)));
    }
    @PutMapping("/roles/{id}") Object role(@PathVariable long id, @Valid @RequestBody Requests.Role b, HttpServletRequest q) {
        return Map.of("id", r.role(b, id, app(q), actor(q)));
    }
    @PatchMapping("/roles/{id}/status") Object status(@PathVariable long id, @Valid @RequestBody Requests.Enabled b, HttpServletRequest q) {
        r.roleStatus(id, app(q), b.enabled(), actor(q)); return ok();
    }
    @DeleteMapping("/roles/{id}") Object deleteRole(@PathVariable long id, HttpServletRequest q) {
        r.deleteRole(id, app(q), actor(q)); return ok();
    }
    @GetMapping("/permissions") Object permissions(HttpServletRequest q) { return r.permissions(app(q)); }
    @PostMapping("/permissions") Object permission(@Valid @RequestBody Requests.Permission b, HttpServletRequest q) {
        return Map.of("id", r.permission(b, null, app(q), actor(q)));
    }
    @PutMapping("/permissions/{id}") Object permission(@PathVariable long id, @Valid @RequestBody Requests.Permission b, HttpServletRequest q) {
        return Map.of("id", r.permission(b, id, app(q), actor(q)));
    }
    @DeleteMapping("/permissions/{id}") Object deletePermission(@PathVariable long id, HttpServletRequest q) {
        r.deletePermission(id, app(q), actor(q)); return ok();
    }
    @PutMapping("/roles/{id}/permissions") Object rolePermissions(@PathVariable long id, @Valid @RequestBody Requests.Ids b, HttpServletRequest q) {
        r.rolePermissions(id, app(q), b.ids(), actor(q)); return ok();
    }
    @GetMapping("/users/{id}/roles") Object userRoles(@PathVariable long id, HttpServletRequest q) {
        return r.assignments(id, app(q));
    }
    @PutMapping("/users/{id}/roles") Object userRoles(@PathVariable long id, @Valid @RequestBody Requests.UserRoles b, HttpServletRequest q) {
        r.userRoles(id, app(q), b.ids(), b.expiresInDays(), b.expiries(), actor(q)); return ok();
    }
    // ---- employee self-service (submitted by the business system on the user's behalf) ----
    @PostMapping("/access-requests") Object submitRequest(@Valid @RequestBody Requests.AccessRequestSubmit b, HttpServletRequest q) {
        return governance.submit(app(q), b, actor(q));
    }
    @GetMapping("/access-requests") Object myRequests(@RequestParam String externalId, HttpServletRequest q) {
        return governance.myRequests(app(q), externalId);
    }
    @PostMapping("/access-requests/{id}/cancel") Object cancelRequest(@PathVariable long id, @Valid @RequestBody Requests.AccessRequestCancel b,
                                                                      HttpServletRequest q) {
        governance.cancelOwn(app(q), id, b.externalId(), actor(q));
        return ok();
    }
    @GetMapping("/users/access") Object myAccess(@RequestParam String externalId, HttpServletRequest q) {
        return governance.myAccess(app(q), externalId);
    }
    @PostMapping("/contexts") Object issue(@Valid @RequestBody Requests.Context b, HttpServletRequest q) { return contexts.issue(app(q), b, actor(q)); }
    @GetMapping("/contexts/effective") Object effective(@RequestHeader("X-Context-Token") String token, HttpServletRequest q) { return contexts.effective(app(q), token); }
    @PostMapping("/contexts/check") Object check(@RequestHeader("X-Context-Token") String token, @Valid @RequestBody Requests.Check b, HttpServletRequest q) {
        return contexts.check(app(q), token, b.permissions());
    }
    @DeleteMapping("/contexts") Object revoke(@RequestHeader("X-Context-Token") String token, HttpServletRequest q) {
        contexts.revoke(app(q), token, actor(q)); return ok();
    }
}
