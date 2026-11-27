package com.melonhub.rbac.r30;

import static org.assertj.core.api.Assertions.*;

import com.melonhub.rbac.infrastructure.config.CloudDatabaseConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

/**
 * 对应需求：R30（见 doc/requirements.md）。
 * 验证 local/cloud/test Profile 的目标库防线与 Swarm 服务名兼容性。
 */
class R30_DatabaseTargetGuardUnitTest {

    // =========================================================================
    // 统一测试参数区（各 Profile 名、主机/端口、合法与非法 JDBC URL）
    // =========================================================================
    private static final String PROFILE_LOCAL = "local";
    private static final String PROFILE_CLOUD = "cloud";
    private static final String PROFILE_TEST = "test";
    private static final String LOCAL_HOST = "127.0.0.1";
    private static final String DB_PORT = "3306";
    private static final String CLOUD_HOST = "db.example";
    private static final String SWARM_MYSQL_HOST = "lab_mysql";
    private static final String URL_LOCAL_PROD = "jdbc:mysql://127.0.0.1:3306/melonhub_rbac?sslMode=REQUIRED";
    private static final String URL_LOCAL_TEST = "jdbc:mysql://127.0.0.1:3306/melonhub_rbac_test?sslMode=REQUIRED";
    private static final String URL_CLOUD_PROD = "jdbc:mysql://db.example:3306/melonhub_rbac?sslMode=REQUIRED";
    private static final String URL_SWARM_PROD = "jdbc:mysql://lab_mysql:3306/melonhub_rbac?sslMode=REQUIRED";
    private static final String TEST_USERNAME = "root";
    private static final String TEST_PASSWORD_PLACEHOLDER = "test-only-placeholder";

    private MockEnvironment local() {
        var env = new MockEnvironment()
                .withProperty("LOCAL_DB_HOST", LOCAL_HOST)
                .withProperty("LOCAL_DB_PORT", DB_PORT)
                .withProperty("spring.datasource.url", URL_LOCAL_PROD)
                .withProperty("spring.datasource.username", TEST_USERNAME)
                .withProperty("spring.datasource.password", TEST_PASSWORD_PLACEHOLDER);
        env.setActiveProfiles(PROFILE_LOCAL);
        return env;
    }

    private void validate(MockEnvironment env) {
        CloudDatabaseConfiguration.databaseTargetGuard(env).postProcessBeanFactory(new DefaultListableBeanFactory());
    }

    @DisplayName("R30 local 配置使用本机凭据不依赖云端配置")
    @Test void localProfileUsesItsOwnCredentialsWithoutCloudConfiguration() {
        assertThatCode(() -> validate(local())).doesNotThrowAnyException();
    }

    @DisplayName("R30 cloud 配置强制匹配指定数据库端点")
    @Test void cloudProfileStillRequiresItsConfiguredEndpoint() {
        var env = local().withProperty("CLOUD_DB_HOST", CLOUD_HOST)
                .withProperty("spring.datasource.url", URL_CLOUD_PROD);
        env.setActiveProfiles(PROFILE_CLOUD);
        assertThatCode(() -> validate(env)).doesNotThrowAnyException();
        env.setProperty("spring.datasource.url", URL_LOCAL_PROD);
        assertThatThrownBy(() -> validate(env)).isInstanceOf(IllegalStateException.class);
    }

    @DisplayName("R30 cloud 配置兼容 Swarm 含下划线服务名（如 lab_mysql）")
    @Test void cloudProfileAcceptsSwarmServiceNamesWithUnderscores() {
        var env = local().withProperty("CLOUD_DB_HOST", SWARM_MYSQL_HOST)
                .withProperty("spring.datasource.url", URL_SWARM_PROD);
        env.setActiveProfiles(PROFILE_CLOUD);
        assertThatCode(() -> validate(env)).doesNotThrowAnyException();
    }

    @DisplayName("R30 local 配置禁止指向非回环远程主机")
    @Test void localProfileCannotBeRedirectedToARemoteHost() {
        var env = local().withProperty("LOCAL_DB_HOST", CLOUD_HOST)
                .withProperty("spring.datasource.url", URL_CLOUD_PROD);
        assertThatThrownBy(() -> validate(env)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @DisplayName("R30 local 配置拦截错库名、错端口或禁用 TLS 参数")
    @ValueSource(strings = {
        "jdbc:mysql://127.0.0.1:3306/other_project?sslMode=REQUIRED",
        "jdbc:mysql://127.0.0.1:3306/melonhub_rbac_test?sslMode=REQUIRED",
        "jdbc:mysql://127.0.0.1:3307/melonhub_rbac?sslMode=REQUIRED",
        "jdbc:mysql://127.0.0.1:3306/melonhub_rbac?sslMode=DISABLED",
        "jdbc:mysql://127.0.0.1:3306/melonhub_rbac?sslMode=REQUIRED&sessionVariables=x",
        "jdbc:mysql://127.0.0.1:3306/melonhub_rbac?sslMode=REQUIRED&sslMode=DISABLED"
    })
    void localProfileRejectsWrongDatabasePortOrUnsafeOptions(String url) {
        assertThatThrownBy(() -> validate(local().withProperty("spring.datasource.url", url))).isInstanceOf(IllegalStateException.class);
    }

    @DisplayName("R30 禁止同时激活冲突的 cloud 与 local Profile")
    @Test void rejectsAmbiguousProfiles() {
        var env = local();
        env.setActiveProfiles(PROFILE_CLOUD, PROFILE_LOCAL);
        assertThatThrownBy(() -> validate(env)).isInstanceOf(IllegalStateException.class);
    }

    @DisplayName("R30 test 配置强制显式目标并锁定独立测试库 melonhub_rbac_test")
    @Test void testsRequireAnExplicitLocalTargetAndTheIsolatedSchema() {
        var env = local();
        env.setActiveProfiles(PROFILE_TEST);
        assertThatThrownBy(() -> validate(env)).isInstanceOf(IllegalStateException.class);
        env.setProperty("TEST_DB_TARGET", PROFILE_LOCAL);
        assertThatThrownBy(() -> validate(env)).isInstanceOf(IllegalStateException.class);
        env.setProperty("spring.datasource.url", URL_LOCAL_TEST);
        assertThatCode(() -> validate(env)).doesNotThrowAnyException();
        env.setProperty("TEST_DB_TARGET", "automatic");
        assertThatThrownBy(() -> validate(env)).isInstanceOf(IllegalStateException.class);
    }
}
