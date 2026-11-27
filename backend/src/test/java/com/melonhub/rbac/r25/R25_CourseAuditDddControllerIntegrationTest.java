package com.melonhub.rbac.r25;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R25_CourseAudit 真实 MySQL 数据库 Controller 层全量测试（DDD 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=ddd")
class R25_CourseAuditDddControllerIntegrationTest extends R25_CourseAuditControllerContractSupport {}
