# 业务 API 说明

本文供业务软件后端开发人员阅读。控制台操作步骤见 [项目使用说明书](使用说明书.md)，模型与信任边界见 [架构说明](ARCHITECTURE.md)。

## 地址与认证

本机开发接口前缀为 `http://127.0.0.1:8080/api/v1`。浏览器控制台通过 Vite 的 `/api` 代理访问后端，后端再通过 TLS 连接云端 MySQL。

所有业务请求需要 `X-API-Key`，查询、校验和注销权限上下文还需要 `X-Context-Token`。API Key 对应的软件由服务端决定，调用方不能通过参数切换到其他软件。密钥只交给业务后端保存，不应下发给普通用户浏览器。

业务后端必须先认证真实用户，再提交可信的 `externalId`。本平台提供授权判断，不代替业务系统的用户登录，也不执行业务操作。

## 接口清单

| 方法 | 路径 | 请求与目的 |
| --- | --- | --- |
| GET | `/departments` | 查询部门，无请求体 |
| GET | `/departments/{departmentId}/users` | 查询部门成员，无请求体 |
| GET、POST | `/departments/{departmentId}/roles` | 查询或创建角色；创建体为 `{"name":"只读角色","code":"reader","description":""}` |
| PUT、DELETE | `/departments/{departmentId}/roles/{roleId}` | 更新或删除角色；更新字段与创建一致 |
| PATCH | `/departments/{departmentId}/roles/{roleId}/status` | 启停角色；`{"enabled":false}` |
| GET、POST | `/departments/{departmentId}/permissions` | 查询或创建权限；创建体为 `{"name":"查看项目","code":"project:read","resource":"project","action":"read"}` |
| PUT、DELETE | `/departments/{departmentId}/permissions/{permissionId}` | 更新或删除权限；更新字段与创建一致 |
| PUT | `/departments/{departmentId}/roles/{roleId}/permissions` | `{"ids":[1,2]}`，整体替换该角色的权限集合 |
| GET、PUT | `/departments/{departmentId}/users/{userId}/roles` | 查询或整体替换该用户在当前隔离域的角色；更新体为 `{"ids":[1,2]}` |
| POST | `/contexts` | `{"externalId":"sample-user","departmentId":1,"activeRoleIds":[1]}`，创建上下文或切换部门 |
| GET | `/contexts/effective` | 查询当前有效角色和权限，需要上下文头 |
| POST | `/contexts/check` | `{"permissions":["project:read"]}`，逐项校验，需要上下文头 |
| DELETE | `/contexts` | 注销该上下文，无请求体，需要上下文头 |

路径中的 ID 仅为示例，应先查询真实 ID。省略 `activeRoleIds` 表示激活全部已分配角色；传空数组表示不激活角色；传入的角色必须是当前域已分配且启用的角色子集。关联接口中的空 `ids` 表示清空关联，传入列表是整体替换，并非追加。

权限编码区分大小写，服务不会解析其中的冒号或业务含义。`resource` 与 `action` 是描述信息，不构成额外授权规则。

## 创建与校验示例

以下为 Bash/curl 示例，全部凭据位置都是占位符：

```bash
curl -X POST http://127.0.0.1:8080/api/v1/contexts \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: YOUR_SOFTWARE_API_KEY' \
  -d '{"externalId":"sample-user","departmentId":1}'

curl -X POST http://127.0.0.1:8080/api/v1/contexts/check \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: YOUR_SOFTWARE_API_KEY' \
  -H 'X-Context-Token: RETURNED_CONTEXT_TOKEN' \
  -d '{"permissions":["project:read","project:create"]}'
```

创建响应包含原始 `contextToken`、部门、软件、上下文版本、到期时间和有效权限。原始 token 仅在创建时返回，后续应由调用方妥善保管。

校验响应示例：

```json
{"allowed":false,"decisions":{"project:read":true,"project:create":false},"contextVersion":4}
```

`decisions` 逐项返回结果，只有全部请求的权限都通过时 `allowed` 才为 true；不存在或未获授权的权限均为 false。

## 部门切换与失效处理

同一用户的当前部门跨软件、跨设备共享。在另一部门创建上下文，会使旧部门的全部软件上下文失效；切回原部门也不会恢复旧 token。同一部门为不同软件创建上下文可同时保持有效。

收到 `CONTEXT_INVALID` 时应丢弃旧 token，根据已认证用户和当前部门重新创建上下文。业务后端每次受保护操作前都应校验，不能离线缓存成功结果后反复放行。平台无法撤回已在业务软件中执行的操作。

## 错误约定

| HTTP 状态 | 错误码 | 含义 |
| --- | --- | --- |
| 400 | INVALID_REQUEST | 字段缺失、请求体或格式不合法 |
| 401 | UNAUTHORIZED | 管理员会话或软件 API Key 缺失、无效 |
| 401 | CONTEXT_INVALID | 上下文过期、注销、部门版本失效或不属于当前软件 |
| 403 | FORBIDDEN | 跨域关联、非部门成员、用户停用或激活了未分配角色 |
| 404 | NOT_FOUND | 记录或隔离域不存在 |
| 409 | CONFLICT | 编码重复，或关联关系阻止删除 |
| 429 | RATE_LIMITED | 管理员登录尝试过于频繁 |

错误体统一为 `{"code":"...","message":"..."}`。权限不足应按拒绝处理，不能将网络错误或未知权限视为允许。

## 平台管理接口

`POST /api/auth/login` 接收 `{"username":"admin","password":"占位符"}`，成功返回管理员 bearer token。`GET /api/auth/me` 检查会话，`POST /api/auth/logout` 注销会话。所有 `/api/admin/**` 请求需要 `Authorization: Bearer ...`。

管理接口主要包括 `/api/admin/applications`、`/departments`、`/users`、`/roles`、`/permissions`、`/overview`、`/audit`、`/contexts`。角色、权限和域内关联操作须指定 `departmentId` 与 `applicationId` 查询参数。

创建软件与 `POST /api/admin/applications/{id}/rotate-key` 会返回一次新的 API Key，后续列表不会回显。重置后原 Key 立即失效；业务后端须同步新 Key。

用户身份、部门成员关系由平台维护；业务 API 仅能管理本软件内的域内角色、权限及关联。
