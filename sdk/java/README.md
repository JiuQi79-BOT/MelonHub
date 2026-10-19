# melonhub-client（Java SDK）

业务后端接入 MelonHub 统一权限平台的 Java 客户端。基于 Java 17 自带的 `java.net.http.HttpClient`，无强制 Spring 依赖；Spring Boot 环境下提供自动装配与注解式权限拦截。

## 安装

坐标：

```xml
<dependency>
    <groupId>com.melonhub</groupId>
    <artifactId>melonhub-client</artifactId>
    <version>1.0.0</version>
</dependency>
```

依赖 Jackson 和 SLF4J API（`slf4j-api` 为编译期依赖，随 SDK 传递；`MelonHubAuthorizer` 通过 SLF4J 记录 fail-closed 告警，非 Spring 项目需要自行提供一个 SLF4J 实现才能看到日志）。Spring Web 相关依赖为可选。

当前未发布到远程仓库，需要先本地安装：

```powershell
mvn -f sdk/java/pom.xml -B -ntp install
```

## 方式一：直接使用客户端

```java
MelonHubClient client = MelonHubClient.builder("http://127.0.0.1:8080", "mh_xxx")
        .connectTimeout(Duration.ofSeconds(3))
        .requestTimeout(Duration.ofSeconds(5))
        .maxRetries(2)
        .build();

// 创建上下文（activeRoleIds 传 null = 全部已分配启用角色）。
// 第四个参数 false 表示不切换部门：用户当前部门不是 1 时抛 DepartmentMismatchException。
ContextView ctx = client.issueContext("u1001", 1L, null, false);
// 三个参数的重载与平台默认一致，部门不同时会切换部门，使该用户在其他部门的上下文全部失效。

// 校验权限
CheckResult result = client.check(ctx.getContextToken(), List.of("item:read", "item:write"));
if (result.allowed()) { /* ... */ }

// 只读查询授权（不创建上下文）、申请角色、撤销
UserAccessView access = client.userAccess("u1001", 1L);
client.submitAccessRequest("u1001", 1L, 5L, "项目需要", 30);
client.revoke(ctx.getContextToken());
```

## 方式二：Spring Boot 自动装配 + 注解

`application.yml`：

```yaml
melonhub:
  client:
    base-url: http://127.0.0.1:8080
    api-key: mh_xxx            # 配置后即启用自动装配
    # user-header: X-MelonHub-User
    # department-header: X-MelonHub-Department
    # request-timeout-millis: 5000
    # max-retries: 2
```

控制器上加注解，身份由网关在请求头中携带：

```java
@GetMapping("/items")
@RequirePermission("item:read")
public List<Item> list() { ... }
```

缺少身份头返回 401 `{"code":"UNAUTHORIZED"}`，无权限返回 403 `{"code":"FORBIDDEN"}`，用户当前部门不是请求头中的部门返回 409 `{"code":"DEPARTMENT_MISMATCH"}`（由业务系统引导用户显式切换部门）。

也可以注入 `MelonHubAuthorizer` 自行判断：

```java
boolean ok = authorizer.hasPermission("u1001", 1L, "item:write");
// 用户在界面上主动切换部门时：
authorizer.switchDepartment("u1001", 2L);
```

`hasPermission` 与 `token` 只在用户的当前部门内创建上下文，不会切换部门；用户已在其他部门时抛 `DepartmentMismatchException`。只有 `switchDepartment` 会切换部门，并清理该用户的本地上下文缓存。

## Webhook 验签

```java
String header = request.getHeader("MelonHub-Signature"); // t=<unix秒>,v1=<hex>
String rawBody = /* 原始请求体字符串，不要反序列化后再拼接 */;
if (!WebhookSignatureVerifier.verify(webhookSecret, header, rawBody, 300)) {
    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
}
JsonNode event = new ObjectMapper().readTree(rawBody);
authorizer.handleWebhookEvent(event.path("type").asText(), event.path("data"));
// 事件如 user.disabled / user.roles.changed / access.approved 会自动清理本地上下文缓存
```

## 失败语义

| 场景 | 行为 |
| --- | --- |
| 429 / 503（带 `Retry-After` 秒数头） | `MelonHubClient` 内部按秒退避重试，默认最多 2 次；耗尽后抛 `MelonHubApiException` |
| 401 且 `code=CONTEXT_INVALID`（失效/过期/跨软件） | 抛 `ContextInvalidException`；`MelonHubAuthorizer.hasPermission` 自动在同一部门重建上下文重试一次 |
| 409 且 `code=DEPARTMENT_MISMATCH` | 抛 `DepartmentMismatchException`（`MelonHubApiException` 子类）；`hasPermission` 不吞掉它，也不会把部门切回来 |
| 其他 4xx / 5xx | 抛 `MelonHubApiException`（含 status/code/message/requestId） |
| 网络中断、超时 | 抛 `MelonHubException`（无状态码）；单次请求超时覆盖响应头和完整响应体，收到头后响应体停滞也会结束请求 |
| `hasPermission` 遇 5xx / 网络故障 | **fail-closed**：返回 false 并记 SLF4J warn |
| 上下文缓存 | 按 (externalId, departmentId) 内存缓存，距过期 60 秒内视为过期；可 `evict` / `evictAll` / Webhook 事件清理 |

Webhook 按 `externalId`、`departmentId` 清理对应缓存；只有部门时清理该部门的全部用户，两者都没有时清理当前客户端的全部缓存。角色启停、删除、权限编码修改或删除以及强制下线均会通知。无 Spring MVC 或 Servlet 的 Spring Boot 应用只装配客户端和授权器。

注意：平台不撤回已执行的外部业务操作；业务侧应对关键操作在执行为止前调用 `check`，而不是只依赖缓存结果。
