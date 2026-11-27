package com.melonhub.rbac.r06;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R6 角色成员替换多连接防死锁并发测试（DDD 后端策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=ddd")
class R06_RoleMembersConcurrencyDddIntegrationTest extends R06_RoleMembersConcurrencyIntegrationTest {
}
