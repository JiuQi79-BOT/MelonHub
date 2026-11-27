package com.melonhub.rbac.r21;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R21_Audit 真实 MySQL 数据库 Controller 层全量测试（DDD 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=ddd")
class R21_AuditDddControllerIntegrationTest extends R21_AuditControllerContractSupport {}
