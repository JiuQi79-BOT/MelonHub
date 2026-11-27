package com.melonhub.rbac.r22;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R22_ApiGuard 真实 MySQL 数据库 Controller 层全量测试（DDD 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=ddd")
class R22_ApiGuardDddControllerIntegrationTest extends R22_ApiGuardControllerContractSupport {}
