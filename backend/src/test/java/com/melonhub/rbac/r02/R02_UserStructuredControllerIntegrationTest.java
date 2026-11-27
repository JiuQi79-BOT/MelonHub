package com.melonhub.rbac.r02;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R02_User 真实 MySQL 数据库 Controller 层全量测试（Structured 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=structured")
class R02_UserStructuredControllerIntegrationTest extends R02_UserControllerContractSupport {}
