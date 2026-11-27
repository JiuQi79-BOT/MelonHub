package com.melonhub.rbac.r13;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R13_AccessReview 真实 MySQL 数据库 Controller 层全量测试（DDD 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=ddd")
class R13_AccessReviewDddControllerIntegrationTest extends R13_AccessReviewControllerContractSupport {}
