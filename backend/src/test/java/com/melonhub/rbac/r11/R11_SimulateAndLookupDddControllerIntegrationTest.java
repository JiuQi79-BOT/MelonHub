package com.melonhub.rbac.r11;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R11_SimulateAndLookup 真实 MySQL 数据库 Controller 层全量测试（DDD 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=ddd")
class R11_SimulateAndLookupDddControllerIntegrationTest extends R11_SimulateAndLookupControllerContractSupport {}
