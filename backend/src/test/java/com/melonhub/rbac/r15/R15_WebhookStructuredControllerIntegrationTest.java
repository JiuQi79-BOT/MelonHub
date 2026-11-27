package com.melonhub.rbac.r15;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R15_Webhook 真实 MySQL 数据库 Controller 层全量测试（Structured 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=structured")
class R15_WebhookStructuredControllerIntegrationTest extends R15_WebhookControllerContractSupport {}
