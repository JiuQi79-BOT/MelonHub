package com.melonhub.rbac.r12;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R12_AccessRequest 真实 MySQL 数据库 Controller 层全量测试（Structured 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=structured")
class R12_AccessRequestStructuredControllerIntegrationTest extends R12_AccessRequestControllerContractSupport {}
