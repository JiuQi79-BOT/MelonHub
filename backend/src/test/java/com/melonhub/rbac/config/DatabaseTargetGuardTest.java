package com.melonhub.rbac.config;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

class DatabaseTargetGuardTest {
    private MockEnvironment local() {
        var env = new MockEnvironment()
                .withProperty("LOCAL_DB_HOST", "127.0.0.1")
                .withProperty("LOCAL_DB_PORT", "3306")
                .withProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:3306/melonhub_rbac?sslMode=REQUIRED")
                .withProperty("spring.datasource.username", "root")
                .withProperty("spring.datasource.password", "test-only-placeholder");
        env.setActiveProfiles("local");
        return env;
    }

    private void validate(MockEnvironment env) {
        CloudDatabaseConfiguration.databaseTargetGuard(env).postProcessBeanFactory(new DefaultListableBeanFactory());
    }

    @Test void localProfileUsesItsOwnCredentialsWithoutCloudConfiguration() {
        assertThatCode(() -> validate(local())).doesNotThrowAnyException();
    }

    @Test void cloudProfileStillRequiresItsConfiguredEndpoint() {
        var env = local().withProperty("CLOUD_DB_HOST", "db.example")
                .withProperty("spring.datasource.url", "jdbc:mysql://db.example:3306/melonhub_rbac?sslMode=REQUIRED");
        env.setActiveProfiles("cloud");
        assertThatCode(() -> validate(env)).doesNotThrowAnyException();
        env.setProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:3306/melonhub_rbac?sslMode=REQUIRED");
        assertThatThrownBy(() -> validate(env)).isInstanceOf(IllegalStateException.class);
    }

    @Test void localProfileCannotBeRedirectedToARemoteHost() {
        var env = local().withProperty("LOCAL_DB_HOST", "db.example")
                .withProperty("spring.datasource.url", "jdbc:mysql://db.example:3306/melonhub_rbac?sslMode=REQUIRED");
        assertThatThrownBy(() -> validate(env)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings={
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

    @Test void rejectsAmbiguousProfiles() {
        var env = local();
        env.setActiveProfiles("cloud", "local");
        assertThatThrownBy(() -> validate(env)).isInstanceOf(IllegalStateException.class);
    }

    @Test void testsRequireAnExplicitLocalTargetAndTheIsolatedSchema() {
        var env = local();
        env.setActiveProfiles("test");
        assertThatThrownBy(() -> validate(env)).isInstanceOf(IllegalStateException.class);
        env.setProperty("TEST_DB_TARGET", "local");
        assertThatThrownBy(() -> validate(env)).isInstanceOf(IllegalStateException.class);
        env.setProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:3306/melonhub_rbac_test?sslMode=REQUIRED");
        assertThatCode(() -> validate(env)).doesNotThrowAnyException();
        env.setProperty("TEST_DB_TARGET", "automatic");
        assertThatThrownBy(() -> validate(env)).isInstanceOf(IllegalStateException.class);
    }
}
