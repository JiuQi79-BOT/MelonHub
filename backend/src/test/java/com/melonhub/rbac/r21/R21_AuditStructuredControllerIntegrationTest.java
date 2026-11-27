package com.melonhub.rbac.r21;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R21_Audit 真实 MySQL 数据库 Controller 层全量测试（Structured 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=structured")
class R21_AuditStructuredControllerIntegrationTest extends R21_AuditControllerContractSupport {}
