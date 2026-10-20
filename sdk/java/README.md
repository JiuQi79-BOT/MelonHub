# melonhub-client（Java SDK）

业务后端接入 MelonHub 统一权限平台的 Java 客户端。基于 Java 17 自带的 `java.net.http.HttpClient`，无强制 Spring 依赖；Spring Boot 环境下提供自动装配与注解式权限拦截。

## 安装

坐标：

```xml
<dependency>
    <groupId>com.melonhub</groupId>
    <artifactId>melonhub-client</artifactId>
    <version>2.0.0</version>
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

// 一个客户端对应 API Key 所属的软件，无需部门参数。
// activeRoleIds 传 null = 全部已分配启用角色，空列表 = 不激活角色。
ContextView ctx = client.issueContext("u1001", null);

// 校验权限
CheckResult result = client.check(ctx.getContextToken(), List.of("item:read", "item:write"));
if (result.allowed()) { /* ... */ }

// 只读查询授权（不创建上下文）、申请角色、撤销
UserAccessView access = client.userAccess("u1001");
client.submitAccessRequest("u1001", 5L, "项目需要", 30);
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
    # request-timeout-millis: 5000
    # max-retries: 2
```

控制器上加注解，身份由网关在请求头中携带：

```java
@GetMapping("/items")
@RequirePermission("item:read")
public List<Item> list() { ... }
```

缺少可信身份头返回 401 `{"code":"UNAUTHORIZED"}`，无权限返回 403 `{"code":"FORBIDDEN"}`。业务网关应覆盖来自外部的同名身份头，只有经过认证的 `externalId` 才能传给平台。

类级 `@RequirePermission` 支持父类及接口上的声明；当前类声明覆盖父类声明，方法声明优先于类声明。

也可以注入 `MelonHubAuthorizer` 自行判断：

```java
boolean ok = authorizer.hasPermission("u1001", "item:write");
String token = authorizer.token("u1001");
```

2.0.0 移除部门参数、`switchDepartment`、`DepartmentMismatchException` 和部门请求头配置；角色与权限只按软件隔离。多个软件使用各自的客户端和 API Key。

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
| 401 且 `code=CONTEXT_INVALID`（失效/过期/跨软件） | 抛 `ContextInvalidException`；`MelonHubAuthorizer.hasPermission` 自动为同一用户和软件重建上下文重试一次 |
| 其他 4xx / 5xx | 抛 `MelonHubApiException`（含 status/code/message/requestId） |
| 网络中断、超时 | 抛 `MelonHubException`（无状态码）；单次请求超时覆盖响应头和完整响应体，收到头后响应体停滞也会结束请求 |
| `hasPermission` 遇 5xx / 网络故障 | **fail-closed**：返回 false 并记 SLF4J warn |
| 上下文缓存 | 在每个客户端内按 externalId 缓存，距过期 60 秒内视为过期；可 `evict` / `evictAll` / Webhook 事件清理 |

Webhook 带 `externalId` 时清理该用户的缓存，否则清理当前客户端的全部缓存。角色启停、删除、权限编码修改或删除以及强制下线均会通知。无 Spring MVC 或 Servlet 的 Spring Boot 应用只装配客户端和授权器。

注意：平台不撤回已执行的外部业务操作；业务侧应对关键操作在执行为止前调用 `check`，而不是只依赖缓存结果。
