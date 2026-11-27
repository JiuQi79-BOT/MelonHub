package com.melonhub.rbac.r03;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R03_Application 真实 MySQL 数据库 Controller 层全量测试（DDD 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=ddd")
class R03_ApplicationDddControllerIntegrationTest extends R03_ApplicationControllerContractSupport {}
