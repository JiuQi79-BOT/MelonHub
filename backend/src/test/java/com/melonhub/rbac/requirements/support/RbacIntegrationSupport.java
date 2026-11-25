package com.melonhub.rbac.requirements.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.melonhub.rbac.CloudTestDatabaseGuard;
import com.melonhub.rbac.dao.AuthDao;
import com.melonhub.rbac.dto.Requests;
import com.melonhub.rbac.security.Credentials;
import com.melonhub.rbac.service.port.ContextUseCase;
import com.melonhub.rbac.service.port.RbacUseCase;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** 核心 RBAC1 集成测试共用固件（组织、软件、双部门/双软件隔离域与角色权限夹具）。 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
@ContextConfiguration(initializers = CloudTestDatabaseGuard.class)
public abstract class RbacIntegrationSupport {
    @Autowired protected RbacUseCase r;
    @Autowired protected ContextUseCase c;
    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected JdbcTemplate db;
    @Autowired protected AuthDao authDao;
    @Value("${melonhub.admin-username}") protected String adminUser;

    protected long d1, d2, a1, a2, u1, u2, r1, r2, rOther, rOtherApp, p1, p2, pOther, pOtherApp;
    protected String external, key1, key2;

    @BeforeEach
    protected void fixture() {
        String suffix = Credentials.token().substring(0, 12);
        external = "user-" + suffix;
        d1 = r.department(new Requests.Department("First", "d1-" + suffix), null, "test");
        d2 = r.department(new Requests.Department("Second", "d2-" + suffix), null, "test");
        var app1 = r.application(new Requests.Application("One", "a1-" + suffix, ""), "test");
        a1 = ((Number) app1.get("id")).longValue();
        key1 = (String) app1.get("apiKey");
        var app2 = r.application(new Requests.Application("Two", "a2-" + suffix, ""), "test");
        a2 = ((Number) app2.get("id")).longValue();
        key2 = (String) app2.get("apiKey");
        u1 = r.user(new Requests.User("User", external, "", List.of(d1, d2)), null, "test");
        u2 = r.user(new Requests.User("Other", external + "-other", "", List.of(d1)), null, "test");
        p1 = r.permission(new Requests.Permission("Read", "item:read", "item", "read"), null, d1, a1, "test");
        p2 = r.permission(new Requests.Permission("Write", "item:write", "item", "write"), null, d1, a1, "test");
        pOther = r.permission(new Requests.Permission("Read", "item:read", "item", "read"), null, d2, a1, "test");
        pOtherApp = r.permission(new Requests.Permission("Read", "item:read", "item", "read"), null, d1, a2, "test");
        r1 = r.role(new Requests.Role("Reader", "reader", ""), null, d1, a1, "test");
        r2 = r.role(new Requests.Role("Writer", "writer", ""), null, d1, a1, "test");
        rOther = r.role(new Requests.Role("Reader", "reader", ""), null, d2, a1, "test");
        rOtherApp = r.role(new Requests.Role("Reader", "reader", ""), null, d1, a2, "test");
        r.rolePermissions(r1, d1, a1, List.of(p1), "test");
        r.rolePermissions(r2, d1, a1, List.of(p2), "test");
        r.rolePermissions(rOther, d2, a1, List.of(pOther), "test");
        r.rolePermissions(rOtherApp, d1, a2, List.of(pOtherApp), "test");
        r.userRoles(u1, d1, a1, List.of(r1, r2), "test");
        r.userRoles(u1, d2, a1, List.of(rOther), "test");
        r.userRoles(u1, d1, a2, List.of(rOtherApp), "test");
    }

    protected String issue(long app, long dept) {
        return (String) c.issue(app, new Requests.Context(external, dept, null), "test").get("contextToken");
    }
}
