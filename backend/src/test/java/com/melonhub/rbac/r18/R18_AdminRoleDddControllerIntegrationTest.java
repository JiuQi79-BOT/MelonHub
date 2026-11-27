package com.melonhub.rbac.r18;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R18_AdminRole 真实 MySQL 数据库 Controller 层全量测试（DDD 策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=ddd")
class R18_AdminRoleDddControllerIntegrationTest extends R18_AdminRoleControllerContractSupport {}
