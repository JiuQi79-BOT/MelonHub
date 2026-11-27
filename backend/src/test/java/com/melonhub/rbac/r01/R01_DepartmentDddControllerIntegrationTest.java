package com.melonhub.rbac.r01;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R01_Department 真实 MySQL 数据库 Controller 层全量测试（DDD 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=ddd")
class R01_DepartmentDddControllerIntegrationTest extends R01_DepartmentControllerContractSupport {}
