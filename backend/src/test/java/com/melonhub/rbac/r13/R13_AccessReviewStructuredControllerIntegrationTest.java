package com.melonhub.rbac.r13;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R13_AccessReview 真实 MySQL 数据库 Controller 层全量测试（Structured 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=structured")
class R13_AccessReviewStructuredControllerIntegrationTest extends R13_AccessReviewControllerContractSupport {}
