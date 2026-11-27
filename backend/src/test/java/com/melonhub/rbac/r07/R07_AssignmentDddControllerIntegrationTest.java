package com.melonhub.rbac.r07;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R07_Assignment 真实 MySQL 数据库 Controller 层全量测试（DDD 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=ddd")
class R07_AssignmentDddControllerIntegrationTest extends R07_AssignmentControllerContractSupport {}
