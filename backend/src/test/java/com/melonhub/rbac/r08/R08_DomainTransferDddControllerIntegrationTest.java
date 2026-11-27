package com.melonhub.rbac.r08;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R08_DomainTransfer 真实 MySQL 数据库 Controller 层全量测试（DDD 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=ddd")
class R08_DomainTransferDddControllerIntegrationTest extends R08_DomainTransferControllerContractSupport {}
