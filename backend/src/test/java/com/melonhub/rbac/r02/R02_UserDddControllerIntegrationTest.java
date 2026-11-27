package com.melonhub.rbac.r02;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R02_User 真实 MySQL 数据库 Controller 层全量测试（DDD 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=ddd")
class R02_UserDddControllerIntegrationTest extends R02_UserControllerContractSupport {}
