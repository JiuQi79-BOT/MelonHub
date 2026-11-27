package com.melonhub.rbac.r34;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R34_RoleHierarchy 真实 MySQL 数据库 Controller 层全量测试（DDD 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=ddd")
class R34_RoleHierarchyDddControllerIntegrationTest extends R34_RoleHierarchyControllerContractSupport {}
