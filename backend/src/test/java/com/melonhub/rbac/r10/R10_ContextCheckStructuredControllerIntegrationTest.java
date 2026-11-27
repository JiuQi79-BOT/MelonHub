package com.melonhub.rbac.r10;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R10_ContextCheck 真实 MySQL 数据库 Controller 层全量测试（Structured 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=structured")
class R10_ContextCheckStructuredControllerIntegrationTest extends R10_ContextCheckControllerContractSupport {}
