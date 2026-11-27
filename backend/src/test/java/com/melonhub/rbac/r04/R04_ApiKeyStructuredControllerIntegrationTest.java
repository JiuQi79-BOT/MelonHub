package com.melonhub.rbac.r04;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R04_ApiKey 真实 MySQL 数据库 Controller 层全量测试（Structured 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=structured")
class R04_ApiKeyStructuredControllerIntegrationTest extends R04_ApiKeyControllerContractSupport {}
