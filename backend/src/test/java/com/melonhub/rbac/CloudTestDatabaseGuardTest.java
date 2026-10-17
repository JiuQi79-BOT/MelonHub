package com.melonhub.rbac;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CloudTestDatabaseGuardTest {
    @Test void acceptsOnlyDedicatedCloudSchemaWithTls() {
        assertThatCode(() -> CloudTestDatabaseGuard.validate("jdbc:mysql://db.example:3306/melonhub_rbac_test?sslMode=REQUIRED", "db.example", "3306")).doesNotThrowAnyException();
    }
    @ParameterizedTest
    @ValueSource(strings={
        "jdbc:mysql://db.example:3306/melonhub_rbac?sslMode=REQUIRED",
        "jdbc:mysql://db.example:3306/melonhub_rbac_test_extra?sslMode=REQUIRED",
        "jdbc:mysql://127.0.0.1:3306/melonhub_rbac_test?sslMode=REQUIRED",
        "jdbc:mysql://db.example:3307/melonhub_rbac_test?sslMode=REQUIRED",
        "jdbc:mysql://db.example:3306/melonhub_rbac_test?sslMode=DISABLED",
        "jdbc:mysql://db.example:3306/melonhub_rbac_test?sslMode=REQUIRED&sessionVariables=x",
        "jdbc:mysql://db.example:3306/melonhub_rbac_test?sslMode=REQUIRED&sslMode=DISABLED",
        "jdbc:mysql://db.example:3306/melonhub_rbac_test"
    })
    void rejectsUnsafeTargetsBeforeConnecting(String url) {
        assertThatThrownBy(() -> CloudTestDatabaseGuard.validate(url, "db.example", "3306")).isInstanceOf(IllegalStateException.class);
    }
}
