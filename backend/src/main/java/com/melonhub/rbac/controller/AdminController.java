package com.melonhub.rbac.controller;

import com.melonhub.rbac.dto.Requests;
import com.melonhub.rbac.exception.ApiException;
import com.melonhub.rbac.service.AuthService;
import com.melonhub.rbac.service.ContextService;
import com.melonhub.rbac.service.RbacService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class AdminController {
    private final RbacService r;
    private final AuthService auth;
    private final ContextService contexts;
    public AdminController(RbacService r,AuthService auth,ContextService contexts) {this.r=r;this.auth=auth;this.contexts=contexts;}
    private String actor(HttpServletRequest req) {return (String)req.getAttribute("actor");}
    @GetMapping("/api/health") Map<String,Object> health() {return r.health();}
    @PostMapping("/api/auth/login") Map<String,Object> login(@Valid @RequestBody Requests.Login body) {return auth.login(body);}
    @GetMapping("/api/auth/me") Map<String,Object> me(HttpServletRequest req) {return Map.of("username",actor(req));}
    @PostMapping("/api/auth/logout") Map<String,Object> logout(@RequestHeader("Authorization") String header) {auth.logout(header);return Map.of("ok",true);}
    @GetMapping("/api/admin/departments") Object departments() {return r.departments();}
    @PostMapping("/api/admin/departments") Object department(@Valid @RequestBody Requests.Department b,HttpServletRequest req) {return Map.of("id",r.department(b,null,actor(req)));}
    @PutMapping("/api/admin/departments/{id}") Object department(@PathVariable long id,@Valid @RequestBody Requests.Department b,HttpServletRequest req) {return Map.of("id",r.department(b,id,actor(req)));}
    @GetMapping("/api/admin/applications") Object applications() {return r.applications();}
    @PostMapping("/api/admin/applications") Object application(@Valid @RequestBody Requests.Application b,HttpServletRequest req) {return r.application(b,actor(req));}
    @PutMapping("/api/admin/applications/{id}") Object application(@PathVariable long id,@Valid @RequestBody Requests.Application b,HttpServletRequest req) {
        return Map.of("id",r.updateApplication(id,b,actor(req)));
    }
    @PostMapping("/api/admin/applications/{id}/rotate-key") Object rotate(@PathVariable long id,HttpServletRequest req) {return Map.of("apiKey",r.rotateKey(id,actor(req)));}
    @GetMapping("/api/admin/users") Object users() {return r.users();}
    @PostMapping("/api/admin/users") Object user(@Valid @RequestBody Requests.User b,HttpServletRequest req) {return Map.of("id",r.user(b,null,actor(req)));}
    @PutMapping("/api/admin/users/{id}") Object user(@PathVariable long id,@Valid @RequestBody Requests.User b,HttpServletRequest req) {return Map.of("id",r.user(b,id,actor(req)));}
    @GetMapping("/api/admin/roles") Object roles(@RequestParam long departmentId,@RequestParam long applicationId) {return r.roles(departmentId,applicationId);}
    @PostMapping("/api/admin/roles") Object role(@RequestParam long departmentId,@RequestParam long applicationId,@Valid @RequestBody Requests.Role b,HttpServletRequest req) {return Map.of("id",r.role(b,null,departmentId,applicationId,actor(req)));}
    @PutMapping("/api/admin/roles/{id}") Object role(@PathVariable long id,@RequestParam long departmentId,@RequestParam long applicationId,@Valid @RequestBody Requests.Role b,HttpServletRequest req) {return Map.of("id",r.role(b,id,departmentId,applicationId,actor(req)));}
    @GetMapping("/api/admin/permissions") Object permissions(@RequestParam long departmentId,@RequestParam long applicationId) {return r.permissions(departmentId,applicationId);}
    @PostMapping("/api/admin/permissions") Object permission(@RequestParam long departmentId,@RequestParam long applicationId,@Valid @RequestBody Requests.Permission b,HttpServletRequest req) {return Map.of("id",r.permission(b,null,departmentId,applicationId,actor(req)));}
    @PutMapping("/api/admin/permissions/{id}") Object permission(@PathVariable long id,@RequestParam long departmentId,@RequestParam long applicationId,@Valid @RequestBody Requests.Permission b,HttpServletRequest req) {return Map.of("id",r.permission(b,id,departmentId,applicationId,actor(req)));}
    @PutMapping("/api/admin/roles/{id}/permissions") Object rolePermissions(@PathVariable long id,@RequestParam long departmentId,@RequestParam long applicationId,@Valid @RequestBody Requests.Ids b,HttpServletRequest req) {r.rolePermissions(id,departmentId,applicationId,b.ids(),actor(req));return Map.of("ok",true);}
    @GetMapping("/api/admin/users/{id}/roles") Object userRoles(@PathVariable long id,@RequestParam long departmentId,@RequestParam long applicationId) {return r.assignments(id,departmentId,applicationId);}
    @PutMapping("/api/admin/users/{id}/roles") Object userRoles(@PathVariable long id,@RequestParam long departmentId,@RequestParam long applicationId,@Valid @RequestBody Requests.Ids b,HttpServletRequest req) {r.userRoles(id,departmentId,applicationId,b.ids(),actor(req));return Map.of("ok",true);}
    @PatchMapping("/api/admin/{entity}/{id}/status") Object status(@PathVariable String entity,@PathVariable long id,@Valid @RequestBody Requests.Enabled b,HttpServletRequest req) {r.enabled(entity,id,b.enabled(),actor(req));return Map.of("ok",true);}
    @DeleteMapping("/api/admin/{entity}/{id}") Object delete(@PathVariable String entity,@PathVariable long id,@RequestParam(required=false) Long departmentId,@RequestParam(required=false) Long applicationId,HttpServletRequest req) {
        if(entity.equals("roles")||entity.equals("permissions")) {if(departmentId==null||applicationId==null)throw ApiException.bad("必须指定隔离域");r.scoped(entity,id,departmentId,applicationId);}
        r.delete(entity,id,actor(req));return Map.of("ok",true);
    }
    @GetMapping("/api/admin/audit") Object audit(@RequestParam(required=false) Long departmentId,@RequestParam(required=false) Long applicationId) {return r.events(departmentId,applicationId);}
    @GetMapping("/api/admin/overview") Object overview(@RequestParam long departmentId,@RequestParam long applicationId) {return r.overview(departmentId,applicationId);}
    @PostMapping("/api/admin/contexts") Object context(@RequestParam long applicationId,@Valid @RequestBody Requests.Context b,HttpServletRequest req) {return contexts.issue(applicationId,b,actor(req));}
    @GetMapping("/api/admin/contexts/effective") Object effective(@RequestParam long applicationId,@RequestHeader("X-Context-Token") String token) {return contexts.effective(applicationId,token);}
    @PostMapping("/api/admin/contexts/check") Object check(@RequestParam long applicationId,@RequestHeader("X-Context-Token") String token,@Valid @RequestBody Requests.Check b) {return contexts.check(applicationId,token,b.permissions());}
}
