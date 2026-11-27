package com.melonhub.rbac.r04;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R04_ApiKey 真实 MySQL 数据库 Controller 层全量测试（DDD 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=ddd")
class R04_ApiKeyDddControllerIntegrationTest extends R04_ApiKeyControllerContractSupport {}
