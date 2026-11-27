package com.melonhub.rbac.r06;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R06_RolePermission 真实 MySQL 数据库 Controller 层全量测试（Structured 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=structured")
class R06_RolePermissionStructuredControllerIntegrationTest extends R06_RolePermissionControllerContractSupport {}
