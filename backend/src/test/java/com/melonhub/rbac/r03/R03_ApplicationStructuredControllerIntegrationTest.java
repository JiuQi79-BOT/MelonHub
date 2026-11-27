package com.melonhub.rbac.r03;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R03_Application 真实 MySQL 数据库 Controller 层全量测试（Structured 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=structured")
class R03_ApplicationStructuredControllerIntegrationTest extends R03_ApplicationControllerContractSupport {}
