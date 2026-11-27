package com.melonhub.rbac.r16;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R16_AdminAuth 真实 MySQL 数据库 Controller 层全量测试（DDD 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=ddd")
class R16_AdminAuthDddControllerIntegrationTest extends R16_AdminAuthControllerContractSupport {}
