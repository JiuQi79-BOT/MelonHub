package com.melonhub.rbac.r14;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * R14/R28 权限缓存代标记失效与角色分配到期通知端到端测试（DDD 后端策略）。
 */
@SpringBootTest(properties = "melonhub.backend-strategy=ddd")
class R14_R28_PermissionCacheDddIntegrationTest extends R14_R28_PermissionCacheIntegrationTest {
}
