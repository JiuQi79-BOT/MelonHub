package com.melonhub.rbac.r15;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R15_Webhook 真实 MySQL 数据库 Controller 层全量测试（DDD 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=ddd")
class R15_WebhookDddControllerIntegrationTest extends R15_WebhookControllerContractSupport {}
