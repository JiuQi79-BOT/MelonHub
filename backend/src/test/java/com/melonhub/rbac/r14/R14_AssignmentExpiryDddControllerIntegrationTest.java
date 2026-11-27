package com.melonhub.rbac.r14;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R14_AssignmentExpiry 真实 MySQL 数据库 Controller 层全量测试（DDD 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=ddd")
class R14_AssignmentExpiryDddControllerIntegrationTest extends R14_AssignmentExpiryControllerContractSupport {}
