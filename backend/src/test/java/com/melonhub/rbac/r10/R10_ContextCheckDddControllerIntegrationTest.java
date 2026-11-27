package com.melonhub.rbac.r10;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R10_ContextCheck 真实 MySQL 数据库 Controller 层全量测试（DDD 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=ddd")
class R10_ContextCheckDddControllerIntegrationTest extends R10_ContextCheckControllerContractSupport {}
