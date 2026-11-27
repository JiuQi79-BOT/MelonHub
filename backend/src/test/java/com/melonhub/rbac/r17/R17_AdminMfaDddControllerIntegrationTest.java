package com.melonhub.rbac.r17;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R17_AdminMfa 真实 MySQL 数据库 Controller 层全量测试（DDD 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=ddd")
class R17_AdminMfaDddControllerIntegrationTest extends R17_AdminMfaControllerContractSupport {}
