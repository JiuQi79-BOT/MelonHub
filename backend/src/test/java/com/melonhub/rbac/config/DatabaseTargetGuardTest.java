package com.melonhub.rbac.config;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

class DatabaseTargetGuardTest {
    private MockEnvironment cloud() {
        var env = new MockEnvironment()
                .withProperty("CLOUD_DB_HOST", "db.example")
                .withProperty("CLOUD_DB_PORT", "3306")
                .withProperty("spring.datasource.url", "jdbc:mysql://db.example:3306/melonhub_rbac?sslMode=REQUIRED")
                .withProperty("spring.datasource.username", "root")
                .withProperty("spring.datasource.password", "test-only-placeholder");
        env.setActiveProfiles("cloud");
        return env;
    }

    private void validate(MockEnvironment env) {
        CloudDatabaseConfiguration.databaseTargetGuard(env).postProcessBeanFactory(new DefaultListableBeanFactory());
    }

    @Test void cloudProfileUsesItsConfiguredCredentials() {
        assertThatCode(() -> validate(cloud())).doesNotThrowAnyException();
    }

    @Test void cloudProfileStillRequiresItsConfiguredEndpoint() {
        var env = cloud().withProperty("CLOUD_DB_HOST", "db.example")
                .withProperty("spring.datasource.url", "jdbc:mysql://db.example:3306/melonhub_rbac?sslMode=REQUIRED");
        env.setActiveProfiles("cloud");
        assertThatCode(() -> validate(env)).doesNotThrowAnyException();
        env.setProperty("spring.datasource.url", "jdbc:mysql://wrong.example:3306/melonhub_rbac?sslMode=REQUIRED");
        assertThatThrownBy(() -> validate(env)).isInstanceOf(IllegalStateException.class);
    }

    @Test void rejectsRemovedLocalProfile() {
        var env = cloud();
        env.setActiveProfiles("local");
        assertThatThrownBy(() -> validate(env)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings={
        "jdbc:mysql://db.example:3306/other_project?sslMode=REQUIRED",
        "jdbc:mysql://db.example:3306/melonhub_rbac_test?sslMode=REQUIRED",
        "jdbc:mysql://db.example:3307/melonhub_rbac?sslMode=REQUIRED",
        "jdbc:mysql://db.example:3306/melonhub_rbac?sslMode=DISABLED",
        "jdbc:mysql://db.example:3306/melonhub_rbac?sslMode=REQUIRED&sessionVariables=x",
        "jdbc:mysql://db.example:3306/melonhub_rbac?sslMode=REQUIRED&sslMode=DISABLED"
    })
    void localProfileRejectsWrongDatabasePortOrUnsafeOptions(String url) {
        assertThatThrownBy(() -> validate(cloud().withProperty("spring.datasource.url", url))).isInstanceOf(IllegalStateException.class);
    }

    @Test void rejectsAmbiguousProfiles() {
        var env = cloud();
        env.setActiveProfiles("cloud", "local");
        assertThatThrownBy(() -> validate(env)).isInstanceOf(IllegalStateException.class);
    }

    @Test void testsRequireTheIsolatedSchemaOnTheCloudEndpoint() {
        var env = cloud();
        env.setActiveProfiles("test");
        assertThatThrownBy(() -> validate(env)).isInstanceOf(IllegalStateException.class);
        env.setProperty("spring.datasource.url", "jdbc:mysql://db.example:3306/melonhub_rbac_test?sslMode=REQUIRED");
        assertThatCode(() -> validate(env)).doesNotThrowAnyException();
    }
}
