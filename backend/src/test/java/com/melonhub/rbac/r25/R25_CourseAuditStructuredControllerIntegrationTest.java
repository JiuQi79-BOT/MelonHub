package com.melonhub.rbac.r25;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R25_CourseAudit 真实 MySQL 数据库 Controller 层全量测试（Structured 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=structured")
class R25_CourseAuditStructuredControllerIntegrationTest extends R25_CourseAuditControllerContractSupport {}
