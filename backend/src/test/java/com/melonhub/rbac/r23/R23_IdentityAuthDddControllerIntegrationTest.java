package com.melonhub.rbac.r23;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R23_IdentityAuth 真实 MySQL 数据库 Controller 层全量测试（DDD 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=ddd")
class R23_IdentityAuthDddControllerIntegrationTest extends R23_IdentityAuthControllerContractSupport {}
