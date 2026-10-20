# MelonHub 统一权限平台

企业内部统一权限服务，为 20 多个业务软件提供配置与在线授权判断。约束为 **角色 × 软件**：软件隔离角色，权限归属角色；部门只用于人员管理、筛选和批量设置软件角色。不同角色可独立维护相同权限编码。

技术栈：Spring Boot 3.5、Spring JDBC、Flyway、云 MySQL、Vue 3、TypeScript、Ant Design Vue 4。Redis 客户端保留，默认关闭，等待服务器部署后启用；MySQL 是唯一持久数据源。

## 主要能力

- 软件角色、角色独立权限、用户多角色与分配期限；在线上下文与批量校验。
- 部门跨页选人、多软件批量追加角色，保留已有有效分配及期限。
- 角色申请审批、权限复核、权限反查、即将到期报告、用户导入和软件级 v2 权限清单。
- 多 API Key、签名 Webhook 与事务外盒、审计哈希链、分级管理员与 TOTP 两步验证。
- 14 页控制台、明暗主题、手机适配、全局搜索、健康检查与监控。
- 可选 Redis 授权缓存、全局限流与故障回退；Java 业务侧 SDK。

## 文档

- [数据库设计](doc/数据库设计.md)：当前关系、唯一键和复合外键、V6 数据迁移。
- [使用说明书](doc/使用说明书.md)：控制台与授权工作流。
- [云数据库运行](doc/云数据库运行.md)：配置、启停、IDEA 和测试。
- [接口说明](doc/API.md)、[架构说明](doc/ARCHITECTURE.md)。
- [验证记录](doc/验证记录.md)、[Java SDK](sdk/java/README.md)。

## 快速使用

1. 按 `.env.example` 配置忽略的根目录 `.env`，只使用云 MySQL。初始管理员密码 12–100 字符且 UTF-8 不超过 72 字节。
2. 双击 `start-all.cmd`，或执行 `./scripts/start-all.ps1 -NoBrowser`。
3. 访问 `http://127.0.0.1:5174`，使用配置中的初始管理员登录。
4. 双击 `stop-all.cmd` 或执行 `./scripts/stop-all.ps1` 停止前后端。

脚本不创建、启动或停止 MySQL/Redis 容器，不修改其他应用。需要 Java 17 或兼容 JDK、Maven、Node.js/npm；首次可执行 `npm --prefix frontend ci --ignore-scripts`。后端 8080、监控 8081、前端 5174 绑定回环地址，端口冲突时报错。IDEA 配置见 `.run/`，图形运行按钮未实测。

## 数据库与升级

正式库 `melonhub_rbac`、测试库 `melonhub_rbac_test` 均为云端入口，强制 TLS。Flyway V1–V5 保留，新 V6 移除部门授权边界、建立软件角色及角色权限复合外键。旧同编码角色保留独立身份，共享权限拆为角色副本，旧上下文撤销。升级前备份，细节见数据库设计文档。

`CACHE_ENABLED=false` 为当前默认值。服务器 Redis 部署后填写 `REDIS_*` 再启用。缓存关闭时访问云 MySQL，限流按实例执行；没有本地数据库回退。

## 验证

```powershell
./scripts/test-backend.ps1
npm --prefix frontend test
npm --prefix frontend run build
mvn -f sdk/java/pom.xml -B -ntp test
Invoke-RestMethod http://127.0.0.1:8080/api/health
```

本次证据与未验证范围见 [验证记录](doc/验证记录.md)。历史本地容器、压测数字不能作为本次云端与服务器 Redis 的验收结果。

## 关键约定

- 部门变化不影响软件角色或已有权限上下文；权限由已激活且未到期的软件角色决定。
- API Key 只保存在业务后端；业务后端先认证用户再提交可信 externalId。
- 每次受保护操作前在线校验，网络故障按拒绝处理；平台不撤回已经执行的业务操作。
- Redis 仅用于缓存和限流；Webhook 接收方先验签再清理缓存。
- Controller、Service 不直接写 SQL；已应用迁移不修改。
- `.env`、`.local`、`output`、构建产物均忽略，不提交凭据。
