package com.melonhub.rbac.controller;

import com.melonhub.rbac.dto.Requests;
import com.melonhub.rbac.service.ContextService;
import com.melonhub.rbac.service.RbacService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class BusinessController {
    private final RbacService r;
    private final ContextService contexts;
    public BusinessController(RbacService r,ContextService contexts) {this.r=r;this.contexts=contexts;}
    private long app(HttpServletRequest q) {return ((Number)q.getAttribute("applicationId")).longValue();}
    private String actor(HttpServletRequest q) {return "app:"+app(q);}
    @GetMapping("/departments") Object departments() {return r.departments();}
    @GetMapping("/departments/{dept}/users") Object users(@PathVariable long dept) {
        return r.departmentUsers(dept);
    }
    @GetMapping("/departments/{dept}/roles") Object roles(@PathVariable long dept,HttpServletRequest q) {return r.roles(dept,app(q));}
    @PostMapping("/departments/{dept}/roles") Object role(@PathVariable long dept,@Valid @RequestBody Requests.Role b,HttpServletRequest q) {return Map.of("id",r.role(b,null,dept,app(q),actor(q)));}
    @PutMapping("/departments/{dept}/roles/{id}") Object role(@PathVariable long dept,@PathVariable long id,@Valid @RequestBody Requests.Role b,HttpServletRequest q) {return Map.of("id",r.role(b,id,dept,app(q),actor(q)));}
    @PatchMapping("/departments/{dept}/roles/{id}/status") Object status(@PathVariable long dept,@PathVariable long id,@Valid @RequestBody Requests.Enabled b,HttpServletRequest q) {r.scoped("roles",id,dept,app(q));r.enabled("roles",id,b.enabled(),actor(q));return Map.of("ok",true);}
    @DeleteMapping("/departments/{dept}/roles/{id}") Object deleteRole(@PathVariable long dept,@PathVariable long id,HttpServletRequest q) {r.scoped("roles",id,dept,app(q));r.delete("roles",id,actor(q));return Map.of("ok",true);}
    @GetMapping("/departments/{dept}/permissions") Object permissions(@PathVariable long dept,HttpServletRequest q) {return r.permissions(dept,app(q));}
    @PostMapping("/departments/{dept}/permissions") Object permission(@PathVariable long dept,@Valid @RequestBody Requests.Permission b,HttpServletRequest q) {return Map.of("id",r.permission(b,null,dept,app(q),actor(q)));}
    @PutMapping("/departments/{dept}/permissions/{id}") Object permission(@PathVariable long dept,@PathVariable long id,@Valid @RequestBody Requests.Permission b,HttpServletRequest q) {return Map.of("id",r.permission(b,id,dept,app(q),actor(q)));}
    @DeleteMapping("/departments/{dept}/permissions/{id}") Object deletePermission(@PathVariable long dept,@PathVariable long id,HttpServletRequest q) {r.scoped("permissions",id,dept,app(q));r.delete("permissions",id,actor(q));return Map.of("ok",true);}
    @PutMapping("/departments/{dept}/roles/{id}/permissions") Object rolePermissions(@PathVariable long dept,@PathVariable long id,@Valid @RequestBody Requests.Ids b,HttpServletRequest q) {r.rolePermissions(id,dept,app(q),b.ids(),actor(q));return Map.of("ok",true);}
    @GetMapping("/departments/{dept}/users/{id}/roles") Object userRoles(@PathVariable long dept,@PathVariable long id,HttpServletRequest q) {return r.assignments(id,dept,app(q));}
    @PutMapping("/departments/{dept}/users/{id}/roles") Object userRoles(@PathVariable long dept,@PathVariable long id,@Valid @RequestBody Requests.Ids b,HttpServletRequest q) {r.userRoles(id,dept,app(q),b.ids(),actor(q));return Map.of("ok",true);}
    @PostMapping("/contexts") Object issue(@Valid @RequestBody Requests.Context b,HttpServletRequest q) {return contexts.issue(app(q),b,actor(q));}
    @GetMapping("/contexts/effective") Object effective(@RequestHeader("X-Context-Token") String token,HttpServletRequest q) {return contexts.effective(app(q),token);}
    @PostMapping("/contexts/check") Object check(@RequestHeader("X-Context-Token") String token,@Valid @RequestBody Requests.Check b,HttpServletRequest q) {return contexts.check(app(q),token,b.permissions());}
    @DeleteMapping("/contexts") Object revoke(@RequestHeader("X-Context-Token") String token,HttpServletRequest q) {contexts.revoke(app(q),token,actor(q));return Map.of("ok",true);}
}
