package com.melonhub.rbac.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.melonhub.rbac.infrastructure.persistence.repository.WebhookDeliveryRepository;
import com.melonhub.rbac.infrastructure.security.Credentials;
import com.melonhub.rbac.infrastructure.security.GlobalRateLimiter;
import com.melonhub.rbac.service.port.application.ApplicationModels.SaveApplicationCommand;
import com.melonhub.rbac.service.port.application.ApplicationUseCase;
import com.melonhub.rbac.service.port.audit.AuditSealUseCase;
import com.melonhub.rbac.service.port.console.ConsoleUseCase;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.SavePermissionCommand;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.SaveRoleCommand;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacUseCase;
import com.melonhub.rbac.service.port.organization.OrganizationModels.SaveDepartmentCommand;
import com.melonhub.rbac.service.port.organization.OrganizationModels.SaveUserCommand;
import com.melonhub.rbac.service.port.organization.OrganizationUseCase;
import com.melonhub.rbac.service.port.security.Visibility;
import com.melonhub.rbac.service.port.webhook.WebhookUseCase;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** Webhook 投递、审计哈希链与全局限流专项集成测试共用固件。 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
@ContextConfiguration(initializers = CloudTestDatabaseGuard.class)
public abstract class GovernanceIntegrationSupport {
    // 统一测试参数区
    protected static final String ACTOR = "gov-test";
    protected static final String DEPT_NAME = "Gov One";
    protected static final String APP_NAME = "Gov App";
    protected static final String USER_ONE_NAME = "GovUser";
    protected static final String USER_TWO_NAME = "GovSecond";
    protected static final String PERM_READ_CODE = "item:read";
    protected static final String PERM_WRITE_CODE = "item:write";
    protected static final String ROLE_READER_CODE = "reader";
    protected static final String ROLE_WRITER_CODE = "writer";
    protected static final String CACHE_DOWN = "DOWN";

    @Autowired protected OrganizationUseCase orgs;
    @Autowired protected ApplicationUseCase apps;
    @Autowired protected DomainRbacUseCase rbac;
    @Autowired protected ConsoleUseCase console;
    @Autowired protected WebhookUseCase webhooks;
    @Autowired protected AuditSealUseCase sealer;
    @Autowired protected GlobalRateLimiter globalRateLimiter;
    @Autowired protected WebhookDeliveryRepository webhookDeliveries;
    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected JdbcTemplate db;

    protected long d1, a1, u1, u2, r1, r2;
    protected String external;

    @BeforeEach
    protected void fixture() {
        String suffix = Credentials.token().substring(0, 12);
        external = "gov-" + suffix;
        d1 = orgs.department(new SaveDepartmentCommand(DEPT_NAME, "gd1-" + suffix), null, ACTOR);
        a1 = apps.application(new SaveApplicationCommand(APP_NAME, "ga-" + suffix, ""), ACTOR).id();
        u1 = orgs.user(new SaveUserCommand(USER_ONE_NAME, external, "", List.of(d1)), null, ACTOR);
        u2 = orgs.user(new SaveUserCommand(USER_TWO_NAME, external + "-second", "", List.of(d1)), null, ACTOR);
        long p1 = rbac.permission(new SavePermissionCommand("Read", PERM_READ_CODE, "item", "read"), null, d1, a1, ACTOR);
        long p2 = rbac.permission(new SavePermissionCommand("Write", PERM_WRITE_CODE, "item", "write"), null, d1, a1, ACTOR);
        r1 = rbac.role(new SaveRoleCommand("Reader", ROLE_READER_CODE, ""), null, d1, a1, ACTOR);
        r2 = rbac.role(new SaveRoleCommand("Writer", ROLE_WRITER_CODE, ""), null, d1, a1, ACTOR);
        rbac.rolePermissions(r1, d1, a1, List.of(p1), ACTOR);
        rbac.rolePermissions(r2, d1, a1, List.of(p2), ACTOR);
        rbac.userRoles(u1, d1, a1, List.of(r1), ACTOR);
    }

    protected void rbacAudit(int i) {
        orgs.userStatus(u2, i % 2 == 0, ACTOR + "-chain");
    }

    protected long lastAuditId() {
        return db.queryForObject("SELECT MAX(id) FROM audit_events", Long.class);
    }

    protected long sealSeq(long id) {
        return db.queryForObject("SELECT seal_seq FROM audit_events WHERE id=?", Long.class, id);
    }

    protected String cacheHealth() {
        try {
            String cache = console.status(Visibility.ALL).cache();
            return cache == null ? CACHE_DOWN : cache;
        } catch (RuntimeException e) {
            return CACHE_DOWN;
        }
    }
}
