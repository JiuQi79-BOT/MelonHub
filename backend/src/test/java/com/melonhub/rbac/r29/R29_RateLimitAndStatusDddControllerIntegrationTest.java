package com.melonhub.rbac.r29;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R29_RateLimitAndStatus 真实 MySQL 数据库 Controller 层全量测试（DDD 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=ddd")
class R29_RateLimitAndStatusDddControllerIntegrationTest extends R29_RateLimitAndStatusControllerContractSupport {}
