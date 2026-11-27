package com.melonhub.rbac.r34;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R34_RoleHierarchy 真实 MySQL 数据库 Controller 层全量测试（Structured 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=structured")
class R34_RoleHierarchyStructuredControllerIntegrationTest extends R34_RoleHierarchyControllerContractSupport {}
