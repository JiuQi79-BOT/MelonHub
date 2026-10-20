package com.melonhub.rbac;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.melonhub.rbac.dao.AuthDao;
import com.melonhub.rbac.dto.Requests;
import com.melonhub.rbac.exception.ApiException;
import com.melonhub.rbac.security.Credentials;
import com.melonhub.rbac.service.ContextService;
import com.melonhub.rbac.service.RbacService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
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
    @Value("${melonhub.admin-username}") String adminUser;
    long d1,d2,a1,a2,u1,u2,r1,r2,rOtherApp,p1,p2,pOtherApp;
    String external,key1,key2;
    @BeforeEach void fixture() {
        String suffix=Credentials.token().substring(0,12);external="user-"+suffix;
        d1=r.department(new Requests.Department("First","d1-"+suffix),null,"test");
        d2=r.department(new Requests.Department("Second","d2-"+suffix),null,"test");
        var app1=r.application(new Requests.Application("One","a1-"+suffix,""),"test");a1=((Number)app1.get("id")).longValue();key1=(String)app1.get("apiKey");
        var app2=r.application(new Requests.Application("Two","a2-"+suffix,""),"test");a2=((Number)app2.get("id")).longValue();key2=(String)app2.get("apiKey");
        u1=r.user(new Requests.User("User",external,"",List.of(d1,d2)),null,"test");
        u2=r.user(new Requests.User("Other",external+"-other","",List.of(d1)),null,"test");
        p1=r.permission(new Requests.Permission("Read","item:read","item","read"),null,a1,"test");
        p2=r.permission(new Requests.Permission("Write","item:write","item","write"),null,a1,"test");
        pOtherApp=r.permission(new Requests.Permission("Read","item:read","item","read"),null,a2,"test");
        r1=r.role(new Requests.Role("Reader","reader",""),null,a1,"test");
        r2=r.role(new Requests.Role("Writer","writer",""),null,a1,"test");
        rOtherApp=r.role(new Requests.Role("Reader","reader",""),null,a2,"test");
        r.rolePermissions(r1,a1,List.of(p1),"test");r.rolePermissions(r2,a1,List.of(p2),"test");
        r.rolePermissions(rOtherApp,a2,List.of(pOtherApp),"test");
        r.userRoles(u1,a1,List.of(r1,r2),"test");r.userRoles(u1,a2,List.of(rOtherApp),"test");
    }
    String issue(long app) {return (String)c.issue(app,new Requests.Context(external,null),"test").get("contextToken");}
    @Test void timestampsAndDatabaseExpiryUseTheSameClock() {
        Instant created = db.queryForObject("SELECT created_at FROM departments WHERE id=?", Timestamp.class, d1).toInstant();
        assertThat(created).isAfter(Instant.now().minusSeconds(30)).isBefore(Instant.now().plusSeconds(5));
        String context = issue(a1);
        assertThat(db.queryForObject("SELECT TIMESTAMPDIFF(SECOND,CURRENT_TIMESTAMP,expires_at) FROM permission_contexts WHERE id=?", Long.class, Credentials.hash(context))).isBetween(3570L,3600L);
        String session = Credentials.hash(Credentials.token());
        authDao.createSession(session,authDao.account(adminUser).orElseThrow(),Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(28800),"127.0.0.1");
        assertThat(db.queryForObject("SELECT TIMESTAMPDIFF(SECOND,CURRENT_TIMESTAMP,expires_at) FROM admin_sessions WHERE token_hash=?", Long.class, session)).isBetween(28770L,28800L);
    }
    @Test void effectivePermissionsAreUnionOfAssignedRolesOnly() {
        String token=issue(a1);
        assertThat(c.effective(a1,token).get("permissions")).isEqualTo(List.of("item:read","item:write"));
        assertThat(c.check(a1,token,List.of("item:read","not:assigned")).get("allowed")).isEqualTo(false);
        String noRoles=(String)c.issue(a1,new Requests.Context(external+"-other",null),"test").get("contextToken");
        assertThat((List<?>)c.effective(a1,noRoles).get("permissions")).isEmpty();
    }
    @Test void creatingAnotherContextKeepsEarlierContextsInBothApplications() {
        String old=issue(a1),other=issue(a2),next=issue(a1);
        assertThat(c.effective(a1,old).get("permissions")).isEqualTo(List.of("item:read","item:write"));
        assertThat(c.effective(a2,other).get("permissions")).isEqualTo(List.of("item:read"));
        assertThat(c.effective(a1,next).get("permissions")).isEqualTo(List.of("item:read","item:write"));
    }
    @Test void differentApplicationsKeepBothContextsValid() {
        String one=issue(a1),two=issue(a2);
        assertThat(c.effective(a1,one).get("permissions")).isEqualTo(List.of("item:read","item:write"));
        assertThat(c.effective(a2,two).get("permissions")).isEqualTo(List.of("item:read"));
    }
    @Test void activeRolesMustBeAssignedSubset() {
        String token=(String)c.issue(a1,new Requests.Context(external,List.of(r1)),"test").get("contextToken");
        assertThat(c.effective(a1,token).get("permissions")).isEqualTo(List.of("item:read"));
        assertThatThrownBy(()->c.issue(a1,new Requests.Context(external,List.of(rOtherApp)),"test")).isInstanceOf(ApiException.class);
        String empty=(String)c.issue(a1,new Requests.Context(external,List.of()),"test").get("contextToken");
        assertThat((List<?>)c.effective(a1,empty).get("permissions")).isEmpty();
    }
    @Test void crossApplicationRolePermissionAssignmentsAreRejectedAtomically() {
        assertThatThrownBy(()->r.rolePermissions(r1,a1,List.of(p1,pOtherApp),"test")).isInstanceOf(ApiException.class);
        assertThat(r.roles(a1).get(0).get("permissionIds")).isEqualTo(List.of(p1));
    }
    @Test void databaseForeignKeysAlsoRejectCrossApplicationRelationships() {
        assertThatThrownBy(()->db.update("INSERT INTO role_permissions(role_id,permission_id,application_id) VALUES (?,?,?)",r1,pOtherApp,a1)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->db.update("INSERT INTO user_roles(user_id,role_id,application_id) VALUES (?,?,?)",u2,rOtherApp,a1)).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void permissionRevocationIsVisibleInExistingContext() {
        String token=issue(a1);r.rolePermissions(r2,a1,List.of(),"test");
        assertThat(c.check(a1,token,List.of("item:write")).get("allowed")).isEqualTo(false);
        assertThat(c.check(a1,token,List.of("item:read")).get("allowed")).isEqualTo(true);
    }
    @Test void removingUserRoleInvalidatesContext() {
        String token=issue(a1);r.userRoles(u1,a1,List.of(r1),"test");
        assertThatThrownBy(()->c.effective(a1,token)).isInstanceOf(ApiException.class);
        assertThat(c.effective(a1,issue(a1)).get("permissions")).isEqualTo(List.of("item:read"));
    }
    @Test void reassigningRolesInOneApplicationKeepsContextsOfOtherApplications() {
        String one=issue(a1),two=issue(a2);
        r.userRoles(u1,a1,List.of(r1),"test");
        assertThatThrownBy(()->c.effective(a1,one)).isInstanceOf(ApiException.class);
        assertThat(c.effective(a2,two).get("permissions")).isEqualTo(List.of("item:read"));
    }
    @Test void disabledUserCannotReuseOldContextAfterReactivation() {
        String token=issue(a1);r.userStatus(u1,false,"test");
        assertThatThrownBy(()->c.effective(a1,token)).isInstanceOf(ApiException.class);
        r.userStatus(u1,true,"test");assertThatThrownBy(()->c.effective(a1,token)).isInstanceOf(ApiException.class);
    }
    @Test void editingDisplayFieldsKeepsContextsButChangingExternalIdEndsThem() {
        String token=issue(a1);
        r.user(new Requests.User("Renamed",external,"renamed@example.com",List.of(d1,d2)),u1,"test");
        assertThat(c.effective(a1,token).get("permissions")).isEqualTo(List.of("item:read","item:write"));
        r.user(new Requests.User("Renamed",external+"-moved","",List.of(d1,d2)),u1,"test");
        assertThatThrownBy(()->c.effective(a1,token)).isInstanceOf(ApiException.class);
    }
    @Test void membershipChangesKeepAssignmentsAndContexts() {
        String token=issue(a1);
        r.user(new Requests.User("User",external,"",List.of()),u1,"test");
        assertThat(c.effective(a1,token).get("permissions")).isEqualTo(List.of("item:read","item:write"));
        assertThat(r.assignments(u1,a1).get("roleIds")).isEqualTo(List.of(r1,r2));
        r.user(new Requests.User("User",external,"",List.of(d2)),u1,"test");
        assertThat(c.effective(a1,token).get("permissions")).isEqualTo(List.of("item:read","item:write"));
        assertThat(r.assignments(u1,a1).get("roleIds")).isEqualTo(List.of(r1,r2));
    }
    @Test void deletingDepartmentOrApplicationInUseNamesWhatRemains() {
        assertThatThrownBy(()->r.deleteDepartment(d2,"test")).isInstanceOf(ApiException.class).hasMessageContaining("1 名成员");
        assertThatThrownBy(()->r.deleteApplication(a2,"test")).isInstanceOf(ApiException.class).hasMessageContaining("1 个角色").hasMessageContaining("1 个权限");
        issue(a1);
        r.user(new Requests.User("User",external,"",List.of(d1)),u1,"test");
        r.deleteDepartment(d2,"test");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM departments WHERE id=?",Long.class,d2)).isZero();
        assertThat(db.queryForObject("SELECT target FROM audit_events WHERE action='DEPARTMENT_DELETED' ORDER BY id DESC LIMIT 1",String.class)).isEqualTo("Second");
        r.deleteRole(rOtherApp,a2,"test");
        r.deletePermission(pOtherApp,a2,"test");
        r.deleteApplication(a2,"test");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM applications WHERE id=?",Long.class,a2)).isZero();
        assertThat(db.queryForObject("SELECT target FROM audit_events WHERE action='APPLICATION_DELETED' ORDER BY id DESC LIMIT 1",String.class)).isEqualTo("Two");
    }
    @Test void usersWithoutDepartmentsCanReceiveRolesAndCreateContexts() {
        long user=r.user(new Requests.User("No department",external+"-none","",List.of()),null,"test");
        r.userRoles(user,a1,List.of(r1),"test");
        String token=(String)c.issue(a1,new Requests.Context(external+"-none",null),"test").get("contextToken");
        assertThat(c.effective(a1,token).get("permissions")).isEqualTo(List.of("item:read"));
    }
    @Test void expiredAndRevokedContextsAreRejected() {
        String token=issue(a1);db.update("UPDATE permission_contexts SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND) WHERE id=?",Credentials.hash(token));
        assertThatThrownBy(()->c.effective(a1,token)).isInstanceOf(ApiException.class);
        String fresh=issue(a1);c.revoke(a1,fresh,"test");assertThatThrownBy(()->c.effective(a1,fresh)).isInstanceOf(ApiException.class);
    }
    @Test void forcedSignOutEndsEveryContextOfTheUser() {
        String one=issue(a1),two=issue(a2);
        assertThat(r.revokeUserContexts(u1,"test")).isEqualTo(2);
        assertThatThrownBy(()->c.effective(a1,one)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->c.effective(a2,two)).isInstanceOf(ApiException.class);
        assertThat(c.effective(a1,issue(a1)).get("permissions")).isEqualTo(List.of("item:read","item:write"));
    }
    @Test void simulationExplainsSoftwareAccessWithoutCreatingContexts() {
        String token=issue(a1);
        var result=r.simulate(new Requests.Simulation(u1,a1,null,List.of("item:read","item:write")));
        assertThat(result.get("eligible")).isEqualTo(true);
        assertThat(result.get("decisions")).isEqualTo(Map.of("item:read",true,"item:write",true));
        assertThat(c.effective(a1,token).get("permissions")).isEqualTo(List.of("item:read","item:write"));
        var unassigned=r.simulate(new Requests.Simulation(u2,a1,null,List.of("item:read")));
        assertThat(unassigned.get("eligible")).isEqualTo(true);
        assertThat(unassigned.get("allowed")).isEqualTo(false);
        var foreignRole=r.simulate(new Requests.Simulation(u1,a1,List.of(rOtherApp),null));
        assertThat(foreignRole.get("eligible")).isEqualTo(false);
    }
    @Test void roleMembersCanBeManagedFromTheRoleSide() {
        r.roleMembers(r1,a1,List.of(u1,u2),"test");
        assertThat(r.roleMembers(r1,a1)).extracting(m->((Number)m.get("id")).longValue()).containsExactly(u1,u2);
        String token=(String)c.issue(a1,new Requests.Context(external+"-other",null),"test").get("contextToken");
        assertThat(c.effective(a1,token).get("permissions")).isEqualTo(List.of("item:read"));
        long outsider=r.user(new Requests.User("Outsider",external+"-out","",List.of(d2)),null,"test");
        r.roleMembers(r1,a1,List.of(u1,u2,outsider),"test");
        assertThat(r.roleMembers(r1,a1)).hasSize(3);
        r.roleMembers(r1,a1,List.of(u1),"test");
        assertThatThrownBy(()->c.effective(a1,token)).isInstanceOf(ApiException.class);
    }
    @Test void roleAndPermissionCodesAreUniqueWithinSoftware() {
        assertThatThrownBy(()->r.role(new Requests.Role("Duplicate","reader",""),null,a1,"test")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->r.permission(new Requests.Permission("Duplicate","item:read","item","read"),null,a1,"test")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(r.roles(a2)).hasSize(1);
        assertThat(r.permissions(a2)).hasSize(1);
    }
    @Test void usersArePagedAndSearchable() {
        var page=r.users(external,null,null,1,1);
        assertThat(page.get("total")).isEqualTo(2L);
        assertThat((List<?>)page.get("items")).hasSize(1);
        assertThat(((Map<?,?>)((List<?>)page.get("items")).get(0)).get("departmentIds")).isEqualTo(List.of(d1,d2));
        assertThat(r.users(external,d2,null,1,20).get("total")).isEqualTo(1L);
        assertThat(r.users("%",null,null,1,20).get("total")).isEqualTo(0L);
        var assignments=r.domainAssignments(d1,a1,external,1,20);
        assertThat(assignments.get("total")).isEqualTo(2L);
    }
    @SuppressWarnings("unchecked")
    @Test void auditRecordsApplicationIdsAndChangeDetails() {
        var events=r.events(null,a1,null,null,"ROLE_PERMISSIONS_UPDATED",null,null,null,1,20);
        assertThat(events.get("total")).isEqualTo(2L);
        assertThat((List<Map<String,Object>>)events.get("items")).allSatisfy(e->assertThat((String)e.get("detail")).startsWith("新增 item:"));
        assertThat(r.events(null,null,List.of(a2),null,"ROLE_PERMISSIONS_UPDATED",null,null,null,1,20).get("total")).isEqualTo(1L);
    }
    @Test void apiKeyIsBoundToApplicationAndCannotAssignForeignRoles() throws Exception {
        mvc.perform(get("/api/v1/roles").param("applicationId",String.valueOf(a2)).header("X-API-Key",key1)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(put("/api/v1/users/"+u1+"/roles").header("X-API-Key",key1).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("ids",List.of(rOtherApp))))).andExpect(status().isForbidden());
        String token=issue(a1);
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
    @Test void applicationsCanHoldSeveralKeysWithExpiryAndRevocation() throws Exception {
        var second=r.createKey(a1,new Requests.ApiKey("second",30),"test");
        String key=(String)second.get("apiKey");
        mvc.perform(get("/api/v1/departments").header("X-API-Key",key)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/departments").header("X-API-Key",key1)).andExpect(status().isOk());
        long first=((Number)r.keys(a1).stream().filter(k->"默认密钥".equals(k.get("name"))).findFirst().orElseThrow().get("id")).longValue();
        r.revokeKey(a1,first,"test");
        assertThatThrownBy(()->r.revokeKey(a1,first,"test")).isInstanceOf(ApiException.class);
        mvc.perform(get("/api/v1/departments").header("X-API-Key",key1)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/departments").header("X-API-Key",key)).andExpect(status().isOk());
        assertThat(r.keys(a1)).extracting(k->k.get("status")).containsExactly("ACTIVE","REVOKED");
        db.update("UPDATE application_keys SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND) WHERE id=?",second.get("id"));
        mvc.perform(get("/api/v1/departments").header("X-API-Key",key)).andExpect(status().isUnauthorized());
    }
    @Test void disabledApplicationLosesKeysAndContexts() throws Exception {
        String token=issue(a1);
        r.applicationStatus(a1,false,"test");
        mvc.perform(get("/api/v1/departments").header("X-API-Key",key1)).andExpect(status().isUnauthorized());
        assertThatThrownBy(()->c.effective(a1,token)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->c.issue(a1,new Requests.Context(external,null),"test")).isInstanceOf(ApiException.class);
    }
    @Test void businessCrudCannotEditOrDeleteRolesInAnotherApplication() throws Exception {
        mvc.perform(put("/api/v1/roles/"+rOtherApp).header("X-API-Key",key1).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Malicious\",\"code\":\"bad\"}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/roles/"+rOtherApp).header("X-API-Key",key1)).andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/roles/"+rOtherApp+"/status").header("X-API-Key",key1).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}")).andExpect(status().isForbidden());
        assertThat(r.roles(a2).get(0).get("name")).isEqualTo("Reader");
        assertThat(r.roles(a2).get(0).get("enabled")).isEqualTo(true);
    }
    @Test void healthReportsDatabaseAndCache() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.database").value("UP")).andExpect(jsonPath("$.cache").exists()).andExpect(jsonPath("$.model").value("RBAC0"));
    }
}
