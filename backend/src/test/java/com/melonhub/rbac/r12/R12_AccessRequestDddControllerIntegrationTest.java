package com.melonhub.rbac.r12;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R12_AccessRequest 真实 MySQL 数据库 Controller 层全量测试（DDD 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=ddd")
class R12_AccessRequestDddControllerIntegrationTest extends R12_AccessRequestControllerContractSupport {}
