package com.melonhub.rbac.r19;

import org.springframework.boot.test.context.SpringBootTest;

/** R19 管理员树层级不变量与双轴范围 —— DDD 策略真实 MySQL Controller 集成测试。 */
@SpringBootTest(properties = "melonhub.backend-strategy=ddd")
class R19_AdminHierarchyDddControllerIntegrationTest extends R19_AdminHierarchyControllerContractSupport {}
