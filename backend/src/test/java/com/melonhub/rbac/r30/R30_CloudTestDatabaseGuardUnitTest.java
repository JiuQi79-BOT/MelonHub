package com.melonhub.rbac.r30;

import static org.assertj.core.api.Assertions.*;

import com.melonhub.rbac.shared.CloudTestDatabaseGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 对应需求：R30（见 doc/requirements.md）。 */
class R30_CloudTestDatabaseGuardUnitTest {

    // =========================================================================
    // 统一测试参数区（预期云端测试主机、端口与唯一合法 JDBC URL）
    // =========================================================================
    private static final String CLOUD_HOST = "db.example";
    private static final String CLOUD_PORT = "3306";
    private static final String VALID_CLOUD_TEST_URL = "jdbc:mysql://db.example:3306/melonhub_rbac_test?sslMode=REQUIRED";

    @DisplayName("R30 云端测试守卫仅放行专用测试库且强制 TLS")
    @Test void acceptsOnlyDedicatedCloudSchemaWithTls() {
        assertThatCode(() -> CloudTestDatabaseGuard.validate(VALID_CLOUD_TEST_URL, CLOUD_HOST, CLOUD_PORT)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @DisplayName("R30 云端测试守卫连接前拦截非测试库、错端口或禁用 TLS 地址")
    @ValueSource(strings = {
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
        assertThatThrownBy(() -> CloudTestDatabaseGuard.validate(url, CLOUD_HOST, CLOUD_PORT)).isInstanceOf(IllegalStateException.class);
    }
}
