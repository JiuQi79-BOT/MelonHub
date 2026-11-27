package com.melonhub.rbac.r23;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R23_IdentityAuth 真实 MySQL 数据库 Controller 层全量测试（Structured 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=structured")
class R23_IdentityAuthStructuredControllerIntegrationTest extends R23_IdentityAuthControllerContractSupport {}
