package com.melonhub.rbac.r09;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R09_ContextIssue 真实 MySQL 数据库 Controller 层全量测试（Structured 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=structured")
class R09_ContextIssueStructuredControllerIntegrationTest extends R09_ContextIssueControllerContractSupport {}
