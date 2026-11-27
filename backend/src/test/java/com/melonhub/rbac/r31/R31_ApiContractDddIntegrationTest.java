package com.melonhub.rbac.r31;

import org.springframework.boot.test.context.SpringBootTest;

/** R31 全量 55 端点 JSON 契约快照（api-shapes.json）—— DDD 策略真实 MySQL 集成测试。 */
@SpringBootTest(properties = "melonhub.backend-strategy=ddd")
class R31_ApiContractDddIntegrationTest extends R31_ApiContractIntegrationTest {}
