package com.melonhub.rbac.r08;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R08_DomainTransfer 真实 MySQL 数据库 Controller 层全量测试（Structured 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=structured")
class R08_DomainTransferStructuredControllerIntegrationTest extends R08_DomainTransferControllerContractSupport {}
