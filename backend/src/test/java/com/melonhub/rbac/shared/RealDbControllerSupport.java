package com.melonhub.rbac.shared;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.melonhub.rbac.api.admin.dto.AccountCreateDTO;
import com.melonhub.rbac.api.admin.dto.LoginDTO;
import com.melonhub.rbac.api.application.dto.ApplicationDTO;
import com.melonhub.rbac.api.domainrbac.dto.PermissionDTO;
import com.melonhub.rbac.api.domainrbac.dto.RoleDTO;
import com.melonhub.rbac.api.organization.dto.DepartmentDTO;
import com.melonhub.rbac.api.organization.dto.UserDTO;
import com.melonhub.rbac.infrastructure.security.Credentials;
import com.melonhub.rbac.service.port.admin.AuthUseCase;
import com.melonhub.rbac.service.port.audit.AuditSealUseCase;
import com.melonhub.rbac.service.port.governance.GovernanceUseCase;
import com.melonhub.rbac.service.port.security.AdminPrincipal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 真实数据库（MySQL 8.3 + Flyway V1–V17 + Redis）Controller 层全链路双策略测试基类：
 * 所有业务操作均经由真实 HTTP {@link MockMvc} -> {@code ApiGuard} -> {@code AdminFunctionGuard}
 * -> {@code @Valid} -> Controller -> Structured/DDD Service -> Spring Data JPA -> 真实 MySQL 落库，
 * 并通过 {@code @Transactional} 自动回滚保持测试间完全隔离。
 */
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
@ContextConfiguration(initializers = CloudTestDatabaseGuard.class)
public abstract class RealDbControllerSupport {
    protected static final String PATH_AUTH_LOGIN = "/api/auth/login";
    protected static final String PATH_ADMIN_ACCOUNTS = "/api/admin/accounts";
    protected static final String PATH_ADMIN_DEPARTMENTS = "/api/admin/departments";
    protected static final String PATH_ADMIN_APPLICATIONS = "/api/admin/applications";
    protected static final String PATH_ADMIN_USERS = "/api/admin/users";
    protected static final String PATH_ADMIN_ROLES = "/api/admin/roles";
    protected static final String PATH_ADMIN_PERMISSIONS = "/api/admin/permissions";
    protected static final String TEST_ADMIN_PASSWORD = "TestAdminPass!2026";

    protected record AppFixture(long id, String apiKey, long keyId) {}
    protected record AdminFixture(long id, String username, String bearer) {}

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected JdbcTemplate db;
    @Autowired protected AuthUseCase authUseCase;
    @Autowired protected GovernanceUseCase governanceUseCase;
    @Autowired protected AuditSealUseCase auditSealUseCase;
    @Value("${melonhub.admin-username}") protected String bootstrapUsername;
    @Value("${melonhub.admin-password}") protected String bootstrapPassword;

    protected String tag;
    protected String superBearer;

    @BeforeEach
    void initBaseFixture() throws Exception {
        this.tag = Credentials.token().substring(0, 8);
        this.superBearer = loginBearer(bootstrapUsername, bootstrapPassword);
    }

    protected String unique(String prefix) {
        return prefix + "-" + tag + "-" + Credentials.token().substring(0, 4);
    }

    protected String json(Object body) throws Exception {
        return mapper.writeValueAsString(body);
    }

    protected JsonNode parseJson(String content) throws Exception {
        return mapper.readTree(content);
    }

    protected String loginBearer(String username, String password) throws Exception {
        return loginBearer(username, password, null);
    }

    protected String loginBearer(String username, String password, String mfaCode) throws Exception {
        String body = mvc.perform(post(PATH_AUTH_LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginDTO(username, password, mfaCode))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + parseJson(body).get("token").asText();
    }

    protected AdminFixture createAdminAndLogin(String prefix, String role, String kind,
                                               List<Long> departmentIds, List<Long> applicationIds) throws Exception {
        String username = unique(prefix).replace('_', '-');
        Boolean allApps = applicationIds == null ? Boolean.TRUE : Boolean.FALSE;
        Boolean allDepts = departmentIds == null ? Boolean.TRUE : Boolean.FALSE;
        var dto = new AccountCreateDTO(username, "测试-" + username, role, TEST_ADMIN_PASSWORD, true,
                applicationIds, allApps, null, null, kind, allDepts, departmentIds);
        String body = mvc.perform(post(PATH_ADMIN_ACCOUNTS)
                        .header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long id = parseJson(body).get("id").asLong();
        return new AdminFixture(id, username, loginBearer(username, TEST_ADMIN_PASSWORD));
    }

    protected AdminFixture createAuditor() throws Exception {
        return createAdminAndLogin("aud", AdminPrincipal.AUDITOR, AdminPrincipal.MANAGER, null, null);
    }

    protected AdminFixture createAppAdmin(List<Long> appIds) throws Exception {
        return createAdminAndLogin("appadm", AdminPrincipal.APP_ADMIN, AdminPrincipal.MANAGER, null, appIds);
    }

    protected AdminFixture createDeptAdmin(List<Long> deptIds) throws Exception {
        return createAdminAndLogin("deptadm", AdminPrincipal.DEPARTMENT_ADMIN, AdminPrincipal.DEPARTMENT, deptIds, null);
    }

    protected long createDepartment(String name, String code) throws Exception {
        String body = mvc.perform(post(PATH_ADMIN_DEPARTMENTS)
                        .header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new DepartmentDTO(name, code))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return parseJson(body).get("id").asLong();
    }

    protected AppFixture createApplication(String name, String code) throws Exception {
        String body = mvc.perform(post(PATH_ADMIN_APPLICATIONS)
                        .header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ApplicationDTO(name, code, "desc", "owner"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = parseJson(body);
        return new AppFixture(node.get("id").asLong(), node.get("apiKey").asText(), node.get("keyId").asLong());
    }

    protected long createUser(String name, String externalId, String email, List<Long> deptIds) throws Exception {
        String body = mvc.perform(post(PATH_ADMIN_USERS)
                        .header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UserDTO(name, externalId, email, deptIds))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return parseJson(body).get("id").asLong();
    }

    protected long createRole(long deptId, long appId, String name, String code, String desc) throws Exception {
        String body = mvc.perform(post(PATH_ADMIN_ROLES)
                        .param("departmentId", String.valueOf(deptId))
                        .param("applicationId", String.valueOf(appId))
                        .header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RoleDTO(name, code, desc))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return parseJson(body).get("id").asLong();
    }

    protected long createPermission(long deptId, long appId, String name, String code,
                                    String resource, String action) throws Exception {
        String body = mvc.perform(post(PATH_ADMIN_PERMISSIONS)
                        .param("departmentId", String.valueOf(deptId))
                        .param("applicationId", String.valueOf(appId))
                        .header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PermissionDTO(name, code, resource, action))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return parseJson(body).get("id").asLong();
    }
}
