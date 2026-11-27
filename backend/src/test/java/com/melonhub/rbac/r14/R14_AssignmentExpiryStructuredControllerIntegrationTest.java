package com.melonhub.rbac.r14;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R14_AssignmentExpiry 真实 MySQL 数据库 Controller 层全量测试（Structured 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=structured")
class R14_AssignmentExpiryStructuredControllerIntegrationTest extends R14_AssignmentExpiryControllerContractSupport {}
