package com.melonhub.rbac.r09;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R09_ContextIssue 真实 MySQL 数据库 Controller 层全量测试（DDD 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=ddd")
class R09_ContextIssueDddControllerIntegrationTest extends R09_ContextIssueControllerContractSupport {}
