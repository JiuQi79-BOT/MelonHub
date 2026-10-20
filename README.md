# MelonHub 统一权限平台

企业内部统一权限服务，为 20 多个业务软件提供角色、权限配置和在线授权判断。按 **软件** 隔离角色与权限；部门仅用于组织用户和批量选人，严格保持 RBAC0：用户与角色、角色与权限均为多对多，没有用户直接权限、角色继承或业务逻辑。

技术栈：Spring Boot 3.5 + Spring JDBC + Flyway + MySQL（唯一数据源）+ Redis（权限缓存与全局限流），前端 Vue 3 + TypeScript + Ant Design Vue 4，业务侧提供 Java SDK。

当前本机位于 `D:\MelonHub`，通过 `local` 配置复用 Docker 的 `xmudemo-mysql`，权限缓存使用本项目专用的 `melonhub-redis` 容器。原有 `cloud` 配置保留，两者显式选择。

## 主要能力

- **在线授权**：业务后端用 API Key 为已认证用户创建权限上下文，每次操作前批量校验权限编码；Redis 缓存加速，授权变更即时失效，Redis 故障时自动回退 MySQL。
- **授权治理**：角色分配可设有效期并自动到期；员工经业务系统申请角色，管理员在审批中心批准或驳回；定期权限复核，关闭时回收标记的授权；即将到期列表与权限反查。
- **变更通知**：每个软件可登记 Webhook，授权变更以事务外盒方式异步推送，带 HMAC-SHA256 签名、退避重试和投递记录。
- **批量与迁移**：按部门跨页选人、一次追加多个角色并个别调整；用户批量导入（部分成功、逐行报错）；普通列表 CSV 导出；按软件导出、导入权限清单；跨软件迁移权限清单。
- **高峰保护**：校验路径不加锁；按软件限流（默认每秒 1000 次，可按软件单独设置），经 Redis 跨实例共享额度，Redis 不可用时回退实例内限流；连接池与锁等待快速失败并返回 `Retry-After`。
- **企业控制台**：14 个页面，涵盖组织与软件、授权管理、审批与复核、权限诊断、审计、系统状态、管理员账号和接入指南；明暗主题、全局搜索（Ctrl/⌘+K）、分页搜索与审计导出。
- **分级管理与登录安全**：超级管理员、平台管理员、软件管理员（只管被授权的软件）、审计员；登录锁定、空闲超时、可选 TOTP 两步验证。
- **密钥管理**：每个软件可有多个 API Key，支持有效期、单独吊销、不停机轮换和紧急轮换。
- **审计**：记录变更详情与来源 IP，审计记录封存为 SHA-256 哈希链，可在控制台校验。
- **可运维**：健康检查区分数据库与缓存状态，`127.0.0.1:8081` 提供 Prometheus 指标，过期数据、到期授权、Webhook 记录和审计自动清理。

## 文档

- **[项目使用说明书](doc/使用说明书.md)**：启停、控制台各页面、从零建立授权链、常见问题。
- [本地 Docker 运行说明](doc/本地Docker运行.md)：MySQL 与 Redis 容器、`.env` 配置、排查。
- [云数据库运行](doc/云数据库运行.md)：显式选择云端入口及测试目标。
- [数据库设计](doc/数据库设计.md)：软件级共享权限结构、V6 升级与分支兼容边界。
- [接口说明](doc/API.md)：业务 API、Webhook、管理 API、错误码、限流和监控。
- [架构说明](doc/ARCHITECTURE.md)：权限模型、缓存失效协议、授权治理、高峰保护、信任边界。
- [验证记录](doc/验证记录.md)：测试、端到端、双实例、浏览器检查以及历史压测与故障演练结果。
- [Java SDK 说明](sdk/java/README.md)：业务后端接入客户端、Spring Boot 注解与 Webhook 验签。

## 快速使用

1. 启动 Docker Desktop。
2. 根据 `.env.example` 配置根目录 `.env`（数据库凭据、`ADMIN_PASSWORD` 12–100 个字符且不超过 72 字节、自行生成的 `REDIS_PASSWORD`）。不要提交、分享或打印该文件。
3. 双击 [start-all.cmd](start-all.cmd)：拉起 MySQL 与 Redis 容器、按需构建，启动前后端并检查健康后打开浏览器。
4. 访问 `http://127.0.0.1:5174`，用 `.env` 中的 `ADMIN_USERNAME` / `ADMIN_PASSWORD` 登录（初始超级管理员）。建议随后在账号菜单中启用两步验证。
5. 双击 [stop-all.cmd](stop-all.cmd) 停止本项目服务和专用 Redis，保留共享 MySQL 和其他程序。

命令行可执行 `./scripts/start-all.ps1 -NoBrowser` 和 `./scripts/stop-all.ps1`。前端固定 5174、后端固定 8080，端口被其他程序占用时明确报错，不自动切换。启动器会复用健康实例，源码或配置更新后先停再启；`-Rebuild` 可强制打包后端。后台日志在 `.local/logs/`。

## 在 IDEA 中运行

1. 打开项目根目录，导入根目录 `pom.xml`，SDK 选择 Java 17（当前为 `C:\Program Files\Java\jdk-17`）。
2. 首次运行执行 `npm --prefix frontend ci --ignore-scripts`（原因见 [本地 Docker 运行说明](doc/本地Docker运行.md)）。
3. 确保 MySQL 与 Redis 容器在运行（可先执行一次一键启动后停止前后端，或在 Docker Desktop 中启动）。
4. 选择 **MelonHub Backend**、**MelonHub Frontend**，或支持 npm 的 IDEA 用 **MelonHub Stack** 同时启动；在 Run/Debug 面板停止或重启。
5. **MelonHub Tests** 使用 `TEST_DB_TARGET` 选择的独立测试库。

共享运行配置位于 `.run/`，不含密码。应用从根目录或 backend 工作目录加载 `.env`，已有进程环境变量优先。无法识别路径的 IDEA 实例请在 Run/Debug 面板管理，避免重复占用 8080。IDEA 图形界面启动按钮尚未实测。Java SDK `sdk/java` 是独立 Maven 工程，不在根 `pom.xml` 聚合中，需要时单独导入。

## 数据库

- 正式库 `melonhub_rbac`，独立测试库 `melonhub_rbac_test`，`utf8mb4` / `utf8mb4_unicode_ci`，连接强制 TLS。
- 表结构由 Flyway 管理：V1 为 RBAC0、上下文、管理员和审计；V2 增加管理员角色、多 API Key、审计详情与查询索引；V3 增加角色分配有效期、软件限额、角色申请、权限复核、Webhook 与投递队列、管理员两步验证和审计哈希链；V4、V5 补齐封存顺序与锚点、治理历史快照、分配标识和会话凭据版本；V6 将授权域改为软件，保留原角色、分配和治理历史。
- 不重新初始化已有 MySQL，不执行实验初始化 SQL，不修改其他应用的数据；`SEED_DEMO=false`，空库通过控制台创建数据。
- 启动保护在 Flyway 前核对所选主机、端口、固定库名和 TLS，错误配置直接停止，不自动切换环境；`local` 只允许回环地址。
- 选择 `cloud` 时连接已有华为云 Swarm 中的 MySQL 9.2.0，字段见 `.env.example` 的 `CLOUD_DB_*`、`SWARM_*`；`./scripts/check-cloud-database.ps1` 只读检查云端。

## 从旧版升级

本版移除了业务 API 和 Java SDK 的部门参数，Java SDK 升为 2.0.0，权限清单改为 version 2。升级前备份项目数据库并停止旧实例，再启动新版执行 V6；不要混跑两种模型。迁移保留每个旧角色及其权限集合、成员与期限；同软件内冲突的角色编码追加 `:legacy:` 后缀，权限按软件和编码合并。旧上下文作废，业务端需要重建。旧清单需从升级后的平台重新导出。

本分支保留软件内多个角色共享权限。上游另一份 `V6__role_owned_permissions.sql` 使用权限归属单角色的结构；已应用该迁移的数据库不能直接切换到本分支，需另行设计转换迁移，不能通过修改 Flyway 历史绕过。详见[数据库设计](doc/数据库设计.md)。

## 命令与验证

```powershell
./scripts/test-backend.ps1
npm --prefix frontend run build
mvn -f sdk/java/pom.xml -B -ntp test
Invoke-RestMethod http://127.0.0.1:8080/api/health
```

当前改造、批量成员操作及上游同步的验证结果见 [验证记录](doc/验证记录.md) 第 1.0.3–1.0.6 节。此前的压测、双实例和故障演练保留为历史记录，不作为新模型的性能证明。

测试脚本与 IDEA JUnit 只允许所选入口的 `melonhub_rbac_test`，错误目标直接失败；测试业务数据随事务回滚或在用例结束时清理。

## 目录

| 路径 | 职责 |
| --- | --- |
| `backend/.../controller` | HTTP 路由：控制台、授权治理、管理员账号、登录与健康、业务 API |
| `backend/.../service` | RBAC0 规则、事务、上下文与校验、认证与两步验证、授权治理、Webhook、审计哈希链、定时清理 |
| `backend/.../dao` | JDBC、SQL、持久化与数据库锁 |
| `backend/.../cache` | Redis 权限缓存与失效协议 |
| `backend/.../security` | 统一鉴权、全局与实例内限流、TOTP、管理员权限、凭据生成与哈希 |
| `backend/.../dto`、`exception`、`config` | 请求校验、统一错误、数据库目标保护与 Redis 配置 |
| `backend/src/main/resources/db/migration` | Flyway 迁移 V1–V6 |
| `frontend/src` | 控制台页面、共享组件、主题、状态与请求封装 |
| `sdk/java` | Java 业务侧 SDK（独立 Maven 工程） |
| `scripts` | 一键启停、Redis 容器管理、测试与云端检查 |
| `doc` | 中文文档 |

`.local`、`output`、`target`、`dist`、`node_modules` 为忽略的工具环境、验证产物或构建产物。

## 关键约定

- 角色与权限属于软件，用户直接获得软件角色。部门归属变化不增减角色、不使上下文失效；批量授权只作用于提交时选中的用户。
- 业务软件必须在每次受保护操作前校验，不能缓存允许结果；网络错误或未知权限一律按拒绝处理。
- API Key 只保存在业务后端，不能下发到浏览器或客户端。
- Webhook 只用于提醒业务侧清理缓存或会话，接收方必须先验签；授权结论始终以在线校验为准。
- Redis 只是缓存和限流计数，可以随时清空；MySQL 是唯一数据源。
