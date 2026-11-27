package com.melonhub.rbac.r29;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R29_RateLimitAndStatus 真实 MySQL 数据库 Controller 层全量测试（Structured 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=structured")
class R29_RateLimitAndStatusStructuredControllerIntegrationTest extends R29_RateLimitAndStatusControllerContractSupport {}
