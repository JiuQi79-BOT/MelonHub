package com.melonhub.rbac.r06;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R06_RolePermission 真实 MySQL 数据库 Controller 层全量测试（DDD 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=ddd")
class R06_RolePermissionDddControllerIntegrationTest extends R06_RolePermissionControllerContractSupport {}
