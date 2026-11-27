package com.melonhub.rbac.r18;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R18_AdminRole 真实 MySQL 数据库 Controller 层全量测试（Structured 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=structured")
class R18_AdminRoleStructuredControllerIntegrationTest extends R18_AdminRoleControllerContractSupport {}
