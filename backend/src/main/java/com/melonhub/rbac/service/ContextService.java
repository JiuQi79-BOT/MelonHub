package com.melonhub.rbac.service;

import com.melonhub.rbac.dao.ContextDao;
import com.melonhub.rbac.dto.Requests;
import com.melonhub.rbac.exception.ApiException;
import com.melonhub.rbac.security.Credentials;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContextService {
    private final ContextDao dao;
    private final RbacService rbac;
    public ContextService(ContextDao dao,RbacService rbac) {this.dao=dao;this.rbac=rbac;}
    @Transactional public Map<String,Object> issue(long app,Requests.Context input,String actor) {
        rbac.domain(input.departmentId(),app);
        var found=dao.lockUserByExternalId(input.externalId());
        if(found.isEmpty())throw ApiException.missing();
        var user=found.get(0);long userId=((Number)user.get("id")).longValue();
        if(!Boolean.TRUE.equals(user.get("enabled")))throw ApiException.forbidden("用户已停用");
        if(!dao.applicationEnabled(app))throw ApiException.forbidden("软件已停用");
        if(!dao.member(userId,input.departmentId()))throw ApiException.forbidden("用户不属于目标部门");
        var assigned=dao.assignedRoles(userId,input.departmentId(),app);
        List<Long> active=input.activeRoleIds()==null?assigned:new ArrayList<>(new LinkedHashSet<>(input.activeRoleIds()));
        if(!assigned.containsAll(active))throw ApiException.forbidden("激活角色必须为用户在当前隔离域内已分配的角色");
        long version=((Number)user.get("context_version")).longValue();
        Object current=user.get("active_department_id");
        // A user has one active department. Switching it invalidates contexts in every application.
        if(current==null || ((Number)current).longValue()!=input.departmentId()) {
            version++;
            dao.switchDepartment(userId,input.departmentId(),version);
        }
        dao.clearStaleContexts(userId,version);
        String token=Credentials.token();Instant expires=Instant.now().plusSeconds(3600);
        dao.create(Credentials.hash(token),userId,input.departmentId(),app,version,expires);
        for(long role:active)dao.activateRole(Credentials.hash(token),role);
        rbac.audit(actor,"CONTEXT_ISSUED",input.externalId(),input.departmentId(),app);
        var result=new LinkedHashMap<String,Object>();result.put("contextToken",token);result.put("expiresAt",expires);result.putAll(effective(app,token));return result;
    }
    @Transactional public Map<String,Object> effective(long app,String token) {
        String hash=Credentials.hash(token);
        var contexts=dao.find(hash,app);
        if(contexts.isEmpty())throw stale();
        var c=contexts.get(0);long user=((Number)c.get("user_id")).longValue();
        // Checks and department switches serialize on the user row, including concurrent requests.
        var users=dao.lockUser(user);
        if(users.isEmpty())throw stale();
        var u=users.get(0);
        if(!Boolean.TRUE.equals(u.get("enabled")) || !Objects.equals(u.get("context_version"),c.get("context_version")) || !Objects.equals(u.get("active_department_id"),c.get("department_id")) || ((Timestamp)c.get("expires_at")).toInstant().isBefore(Instant.now()))throw stale();
        if(!dao.applicationEnabled(app))throw stale();
        if(!dao.member(user,c.get("department_id")))throw stale();
        var roles=dao.effectiveRoles(user,hash,c.get("department_id"),app);
        var permissions=dao.effectivePermissions(user,hash,c.get("department_id"),app);
        return Map.of("userId",user,"departmentId",c.get("department_id"),"applicationId",app,"contextVersion",c.get("context_version"),"roles",roles,"permissions",permissions);
    }
    @Transactional public Map<String,Object> check(long app,String token,List<String> requested) {
        var effective=effective(app,token);
        var permissions=(List<?>)effective.get("permissions");
        Map<String,Boolean> decisions=new LinkedHashMap<>();for(String p:requested)decisions.put(p,permissions.contains(p));
        return Map.of("allowed",decisions.values().stream().allMatch(Boolean::booleanValue),"decisions",decisions,"contextVersion",effective.get("contextVersion"));
    }
    @Transactional public void revoke(long app,String token,String actor) {
        effective(app,token);dao.revoke(Credentials.hash(token),app);
        rbac.audit(actor,"CONTEXT_REVOKED","session",null,app);
    }
    private ApiException stale() {return new ApiException(HttpStatus.UNAUTHORIZED,"CONTEXT_INVALID","权限上下文已失效，请重新创建");}
}
