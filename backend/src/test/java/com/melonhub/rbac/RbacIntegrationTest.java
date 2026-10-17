package com.melonhub.rbac;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.melonhub.rbac.dto.Requests;
import com.melonhub.rbac.exception.ApiException;
import com.melonhub.rbac.security.Credentials;
import com.melonhub.rbac.dao.AuthDao;
import com.melonhub.rbac.service.RbacService;
import com.melonhub.rbac.service.ContextService;
import java.util.List;
import java.util.Map;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
@ContextConfiguration(initializers=CloudTestDatabaseGuard.class)
class RbacIntegrationTest {
    @Autowired RbacService r;
    @Autowired ContextService c;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate db;
    @Autowired AuthDao authDao;
    long d1,d2,a1,a2,u1,u2,r1,r2,rOther,rOtherApp,p1,p2,pOther,pOtherApp;
    String external,key1,key2;
    @BeforeEach void fixture() {
        String suffix=Credentials.token().substring(0,12);external="user-"+suffix;
        d1=r.department(new Requests.Department("First","d1-"+suffix),null,"test");
        d2=r.department(new Requests.Department("Second","d2-"+suffix),null,"test");
        var app1=r.application(new Requests.Application("One","a1-"+suffix,""),"test");a1=((Number)app1.get("id")).longValue();key1=(String)app1.get("apiKey");
        var app2=r.application(new Requests.Application("Two","a2-"+suffix,""),"test");a2=((Number)app2.get("id")).longValue();key2=(String)app2.get("apiKey");
        u1=r.user(new Requests.User("User",external,"",List.of(d1,d2)),null,"test");
        u2=r.user(new Requests.User("Other",external+"-other","",List.of(d1)),null,"test");
        p1=r.permission(new Requests.Permission("Read","item:read","item","read"),null,d1,a1,"test");
        p2=r.permission(new Requests.Permission("Write","item:write","item","write"),null,d1,a1,"test");
        pOther=r.permission(new Requests.Permission("Read","item:read","item","read"),null,d2,a1,"test");
        pOtherApp=r.permission(new Requests.Permission("Read","item:read","item","read"),null,d1,a2,"test");
        r1=r.role(new Requests.Role("Reader","reader",""),null,d1,a1,"test");
        r2=r.role(new Requests.Role("Writer","writer",""),null,d1,a1,"test");
        rOther=r.role(new Requests.Role("Reader","reader",""),null,d2,a1,"test");
        rOtherApp=r.role(new Requests.Role("Reader","reader",""),null,d1,a2,"test");
        r.rolePermissions(r1,d1,a1,List.of(p1),"test");r.rolePermissions(r2,d1,a1,List.of(p2),"test");
        r.rolePermissions(rOther,d2,a1,List.of(pOther),"test");r.rolePermissions(rOtherApp,d1,a2,List.of(pOtherApp),"test");
        r.userRoles(u1,d1,a1,List.of(r1,r2),"test");r.userRoles(u1,d2,a1,List.of(rOther),"test");r.userRoles(u1,d1,a2,List.of(rOtherApp),"test");
    }
    String issue(long app,long dept) {return (String)c.issue(app,new Requests.Context(external,dept,null),"test").get("contextToken");}
    @Test void timestampsAndDatabaseExpiryUseTheSameClock() {
        Instant created = db.queryForObject("SELECT created_at FROM departments WHERE id=?", Timestamp.class, d1).toInstant();
        assertThat(created).isAfter(Instant.now().minusSeconds(30)).isBefore(Instant.now().plusSeconds(5));
        String context = issue(a1,d1);
        assertThat(db.queryForObject("SELECT TIMESTAMPDIFF(SECOND,CURRENT_TIMESTAMP,expires_at) FROM permission_contexts WHERE id=?", Long.class, Credentials.hash(context))).isBetween(3570L,3600L);
        String session = Credentials.hash(Credentials.token());
        authDao.createSession(session,"clock-test",Instant.now().plusSeconds(28800));
        assertThat(db.queryForObject("SELECT TIMESTAMPDIFF(SECOND,CURRENT_TIMESTAMP,expires_at) FROM admin_sessions WHERE token_hash=?", Long.class, session)).isBetween(28770L,28800L);
    }
    @Test void effectivePermissionsAreUnionOfAssignedRolesOnly() {
        String token=issue(a1,d1);
        assertThat(c.effective(a1,token).get("permissions")).isEqualTo(List.of("item:read","item:write"));
        assertThat(c.check(a1,token,List.of("item:read","not:assigned")).get("allowed")).isEqualTo(false);
        String noRoles=(String)c.issue(a1,new Requests.Context(external+"-other",d1,null),"test").get("contextToken");
        assertThat((List<?>)c.effective(a1,noRoles).get("permissions")).isEmpty();
    }
    @Test void departmentSwitchInvalidatesOldContextsAcrossApplicationsAndSwitchBack() {
        String old=issue(a1,d1),oldOther=issue(a2,d1),next=issue(a1,d2);
        assertThatThrownBy(()->c.effective(a1,old)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->c.effective(a2,oldOther)).isInstanceOf(ApiException.class);
        assertThat(c.effective(a1,next).get("permissions")).isEqualTo(List.of("item:read"));
        issue(a1,d1);
        assertThatThrownBy(()->c.effective(a1,next)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->c.effective(a1,old)).isInstanceOf(ApiException.class);
    }
    @Test void sameDepartmentInDifferentApplicationsKeepsBothContextsValid() {
        String one=issue(a1,d1),two=issue(a2,d1);
        assertThat(c.effective(a1,one).get("permissions")).isEqualTo(List.of("item:read","item:write"));
        assertThat(c.effective(a2,two).get("permissions")).isEqualTo(List.of("item:read"));
    }
    @Test void activeRolesMustBeAssignedSubset() {
        String token=(String)c.issue(a1,new Requests.Context(external,d1,List.of(r1)),"test").get("contextToken");
        assertThat(c.effective(a1,token).get("permissions")).isEqualTo(List.of("item:read"));
        assertThatThrownBy(()->c.issue(a1,new Requests.Context(external,d1,List.of(rOtherApp)),"test")).isInstanceOf(ApiException.class);
        String empty=(String)c.issue(a1,new Requests.Context(external,d1,List.of()),"test").get("contextToken");
        assertThat((List<?>)c.effective(a1,empty).get("permissions")).isEmpty();
    }
    @Test void crossDomainRolePermissionAssignmentsAreRejectedAtomically() {
        assertThatThrownBy(()->r.rolePermissions(r1,d1,a1,List.of(p1,pOther),"test")).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->r.rolePermissions(r1,d1,a1,List.of(pOtherApp),"test")).isInstanceOf(ApiException.class);
        assertThat(r.roles(d1,a1).get(0).get("permissionIds")).isEqualTo(List.of(p1));
    }
    @Test void databaseForeignKeysAlsoRejectCrossDomainRelationships() {
        assertThatThrownBy(()->db.update("INSERT INTO role_permissions VALUES (?,?,?,?)",r1,pOther,d1,a1)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->db.update("INSERT INTO user_roles VALUES (?,?,?,?)",u2,rOther,d1,a1)).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void permissionRevocationIsVisibleInExistingContext() {
        String token=issue(a1,d1);r.rolePermissions(r2,d1,a1,List.of(),"test");
        assertThat(c.check(a1,token,List.of("item:write")).get("allowed")).isEqualTo(false);
        assertThat(c.check(a1,token,List.of("item:read")).get("allowed")).isEqualTo(true);
    }
    @Test void removingUserRoleInvalidatesContext() {
        String token=issue(a1,d1);r.userRoles(u1,d1,a1,List.of(r1),"test");
        assertThatThrownBy(()->c.effective(a1,token)).isInstanceOf(ApiException.class);
        assertThat(c.effective(a1,issue(a1,d1)).get("permissions")).isEqualTo(List.of("item:read"));
    }
    @Test void disabledUserCannotReuseOldContextAfterReactivation() {
        String token=issue(a1,d1);r.enabled("users",u1,false,"test");
        assertThatThrownBy(()->c.effective(a1,token)).isInstanceOf(ApiException.class);
        r.enabled("users",u1,true,"test");assertThatThrownBy(()->c.effective(a1,token)).isInstanceOf(ApiException.class);
    }
    @Test void nonmemberCannotSwitchDepartments() {
        assertThatThrownBy(()->c.issue(a1,new Requests.Context(external+"-other",d2,null),"test")).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->r.userRoles(u2,d2,a1,List.of(rOther),"test")).isInstanceOf(ApiException.class);
    }
    @Test void expiredAndRevokedContextsAreRejected() {
        String token=issue(a1,d1);db.update("UPDATE permission_contexts SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND) WHERE id=?",Credentials.hash(token));
        assertThatThrownBy(()->c.effective(a1,token)).isInstanceOf(ApiException.class);
        String fresh=issue(a1,d1);c.revoke(a1,fresh,"test");assertThatThrownBy(()->c.effective(a1,fresh)).isInstanceOf(ApiException.class);
    }
    @Test void apiKeyIsBoundToApplicationAndCannotAssignForeignRoles() throws Exception {
        mvc.perform(get("/api/v1/departments/"+d1+"/roles").param("applicationId",String.valueOf(a2)).header("X-API-Key",key1)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(put("/api/v1/departments/"+d1+"/users/"+u1+"/roles").header("X-API-Key",key1).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("ids",List.of(rOtherApp))))).andExpect(status().isForbidden());
        String token=issue(a1,d1);
        mvc.perform(get("/api/v1/contexts/effective").header("X-API-Key",key2).header("X-Context-Token",token)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("CONTEXT_INVALID"));
    }
    @Test void adminAndBusinessEndpointsRequireDistinctCredentials() throws Exception {
        mvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/users").header("X-API-Key",key1)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/departments").header("Authorization","Bearer fake")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/departments").header("X-API-Key",key1)).andExpect(status().isOk());
    }
    @Test void keyRotationRejectsOldKeyImmediately() throws Exception {
        String fresh=r.rotateKey(a1,"test");
        mvc.perform(get("/api/v1/departments").header("X-API-Key",key1)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/departments").header("X-API-Key",fresh)).andExpect(status().isOk());
    }
    @Test void businessCrudCannotEditOrDeleteRolesInAnotherDomain() throws Exception {
        mvc.perform(put("/api/v1/departments/"+d1+"/roles/"+rOtherApp).header("X-API-Key",key1).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Malicious\",\"code\":\"bad\"}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/departments/"+d1+"/roles/"+rOtherApp).header("X-API-Key",key1)).andExpect(status().isForbidden());
        assertThat(r.roles(d1,a2).get(0).get("name")).isEqualTo("Reader");
    }
}
