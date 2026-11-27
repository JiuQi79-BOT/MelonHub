# MelonHub 统一权限平台

企业内部统一权限服务，为 20 多个业务软件提供角色、权限配置和在线授权判断。按 **部门 × 软件** 隔离，以 RBAC1 角色层级为核心：用户通过角色获得权限，角色可继承同域父角色的权限，禁止循环继承和跨域继承。

技术栈：Spring Boot 3.5 + Spring Data JPA（Hibernate）+ Flyway + MySQL（唯一数据源）+ Redis（权限缓存与全局限流），前端 Vue 3 + TypeScript + Ant Design Vue 4，业务侧提供 Java SDK。

验收使用服务器上的 Docker Swarm。浏览器访问服务器 `5174` 即可使用 MelonHub，服务器后端和数据库负责处理请求。当前部署版本、端口与核验方法见 [Docker Swarm 部署](deploy/README.md) 和 [验证记录](doc/验证记录.md)。

## 主要能力

- **在线授权**：业务后端用 API Key 为已认证用户创建权限上下文，每次操作前批量校验权限编码；Redis 缓存加速，授权变更即时失效，Redis 故障时自动回退 MySQL。
- **授权治理**：角色分配可设有效期并自动到期；员工经业务系统申请角色，管理员在审批中心批准或驳回；定期权限复核，关闭时回收标记的授权；即将到期列表与权限反查。
- **变更通知**：每个软件可登记 Webhook，授权变更以事务外盒方式异步推送，带 HMAC-SHA256 签名、退避重试和投递记录。
- **批量与迁移**：按部门跨页选人、一次追加多个角色并个别调整；用户批量导入（部分成功、逐行报错）；普通列表 CSV 导出；按软件导出、导入权限清单；跨部门复制授权配置。
- **高峰保护**：校验路径不加锁；按软件限流（默认每秒 1000 次，可按软件单独设置），经 Redis 跨实例共享额度，Redis 不可用时回退实例内限流；连接池与锁等待快速失败并返回 `Retry-After`。
- **企业控制台**：15 个页面，涵盖组织与软件、授权管理、审批与复核、权限诊断、审计、系统状态、管理员账号和接入指南；明暗主题、全局搜索（Ctrl/⌘+K）、分页搜索与审计导出。
- **分级管理与登录安全**：超级管理员、平台管理员、软件管理员（只管被授权的软件）、审计员；登录锁定、空闲超时、可选 TOTP 两步验证。
- **密钥管理**：每个软件可有多个 API Key，支持有效期、单独吊销、不停机轮换和紧急轮换。
- **审计**：记录变更详情与来源 IP，审计记录封存为 SHA-256 哈希链，可在控制台校验。
- **可运维**：健康检查区分数据库与缓存状态，`127.0.0.1:8081` 提供 Prometheus 指标，过期数据、到期授权、Webhook 记录和审计自动清理。

## 文档

- **[业务术语表](CONTEXT.md)**：MelonHub 领域与平台核心术语及边界定义。
- **[需求文档（R1–R34）](doc/requirements.md)**：按现行代码整理的完整功能/非功能需求、验收标准、测试场景与执行范围对照表及历史演进裁决。
- **[项目使用说明书](doc/使用说明书.md)**：启停、控制台各页面、从零建立授权链、常见问题。
- [本地 Docker 运行说明](doc/本地Docker运行.md)：MySQL 与 Redis 容器、`.env` 配置、排查。
- [Docker Swarm 部署](deploy/README.md)：服务器镜像构建、Stack 配置和联调端口。
- [接口说明](doc/API.md)：业务 API、Webhook、管理 API、错误码、限流和监控。
- [架构说明](doc/ARCHITECTURE.md)：权限模型、缓存失效协议、授权治理、高峰保护、信任边界。
- [验证记录](doc/验证记录.md)：测试、端到端、双实例、浏览器检查以及历史压测与故障演练结果。
- [Java SDK 说明](sdk/java/README.md)：业务后端接入客户端、Spring Boot 注解与 Webhook 验签。
- [统一身份联调说明](doc/统一身份联调.md)：课程管理系统 9 个统一身份接口、CORS、联调用户和权限初始化。
- [第二轮检查问题与回答](doc/第二轮检查问题与回答.md)：助教/教师检查准备、结构化设计、DDD 和 GRASP 问答。
- [需求与验收对照](doc/需求与验收对照.md)
- [双系统操作手册](doc/双系统操作手册.md)
- [后端 UML 与代码导读](doc/后端UML与代码导读.md)
- [面向对象设计答辩](doc/面向对象设计答辩.md)
- [课程权限接入核对](doc/课程权限接入核对.md)：老师课程系统实际运行镜像、身份客户端及操作权限调用边界。

## 本机开发

1. 启动 Docker Desktop。
2. 按 [本地 Docker 运行说明](doc/本地Docker运行.md) 配置根目录 `.env`，显式选用 `local`。本机需要 Redis 调试时，再配置专用容器和随机密码。
3. 双击 [start-all.cmd](start-all.cmd) 启动本机前后端和所选依赖。
4. 访问 `http://127.0.0.1:5174`，使用所选数据库中的管理员凭据。首次运行空库时，初始管理员由 `.env` 中的 `ADMIN_USERNAME` 和 `ADMIN_PASSWORD` 创建。
5. 双击 [stop-all.cmd](stop-all.cmd) 停止本项目服务和专用 Redis，保留共享 MySQL 和其他程序。

命令行可执行 `./scripts/start-all.ps1 -NoBrowser` 和 `./scripts/stop-all.ps1`。前端固定 5174、后端固定 8080，端口被其他程序占用时明确报错，不自动切换。启动器会复用健康实例，源码或配置更新后先停再启；`-Rebuild` 可强制打包后端。后台日志在 `.local/logs/`。

## 在 IDEA 中运行

1. 打开项目根目录，导入根目录 `pom.xml`，SDK 选择已安装的 Java 17 或兼容 JDK。
2. 首次运行执行 `npm --prefix frontend ci --ignore-scripts`（原因见 [本地 Docker 运行说明](doc/本地Docker运行.md)）。
3. 确保所选 MySQL 在运行。仅在启用本机缓存时启动本项目 Redis。
4. 选择 **MelonHub Backend**、**MelonHub Frontend**，或支持 npm 的 IDEA 用 **MelonHub Stack** 同时启动；在 Run/Debug 面板停止或重启。
5. **MelonHub Tests** 使用 `TEST_DB_TARGET` 选择的独立测试库。

共享运行配置位于 `.run/`，不含密码。应用从根目录或 backend 工作目录加载 `.env`，已有进程环境变量优先。无法识别路径的 IDEA 实例请在 Run/Debug 面板管理，避免重复占用 8080。IDEA 图形界面启动按钮尚未实测。Java SDK `sdk/java` 是独立 Maven 工程，不在根 `pom.xml` 聚合中，需要时单独导入。

## 数据库

- 正式库 `melonhub_rbac`，独立测试库 `melonhub_rbac_test`，`utf8mb4` / `utf8mb4_unicode_ci`，连接强制 TLS。
- 表结构由 Flyway 管理：V1 为基础 RBAC、上下文、管理员和审计；V2 增加管理员角色、多 API Key、审计详情与查询索引；V3 增加角色分配有效期、软件限额、角色申请、权限复核、Webhook 与投递队列、管理员两步验证和审计哈希链；V4、V5 补齐封存顺序与锚点、治理历史快照、分配标识和会话凭据版本；V6–V14 完成旧库兼容、管理员功能、管理员树、统一身份和管理员数字主键迁移；V15、V16 补齐角色权限与用户角色的隔离域复合外键；V17 增加同域角色继承关系。
- 不重新初始化已有 MySQL，不执行实验初始化 SQL，不修改其他应用的数据；`SEED_DEMO=false`，空库通过控制台创建数据。
- 启动保护在 Flyway 前核对所选主机、端口、固定库名和 TLS，错误配置直接停止，不自动切换环境；`local` 只允许回环地址。
- 选择 `cloud` 时连接已有华为云 Swarm 中的 MySQL 9.2.0，字段见 `.env.example` 的 `CLOUD_DB_*`、`SWARM_*`；`./scripts/check-cloud-database.ps1` 只读检查云端。

## 命令与验证

```powershell
./scripts/test-backend.ps1
npm --prefix frontend run build
mvn -f sdk/java/pom.xml -B -ntp test
Invoke-RestMethod http://127.0.0.1:8080/api/health
```

2026-11-24 已验证前端单测、类型检查与构建。服务器 `5174` 的管理员登录、会话及菜单数据请求通过，三个菜单请求与后端日志逐项对应。自动化测试的范围与历史记录见 [验证记录](doc/验证记录.md)。

测试脚本与 IDEA JUnit 只允许所选入口的 `melonhub_rbac_test`，错误目标直接失败；测试业务数据随事务回滚或在用例结束时清理。

## 目录

| 路径 | 职责 |
| --- | --- |
| `backend/.../api` | 按业务模块分包的 17 个 HTTP 控制器、DTO/VO/Mapper 与统一异常处理 `api/shared/error/ApiErrors` |
| `backend/.../service` | `service/port`（13 个用例端口与 Command/Result）、`service/structured`（13 个结构化事务脚本服务）与 `service/ddd`（纯领域模型 + 13 个 DDD 应用服务） |
| `backend/.../infrastructure/persistence` | `entity`（JPA 实体）、`repository`（Spring Data JPA 仓储与自定义 JPQL/悲观锁）与 `gateway`（9 个 `Jpa*Adapter` 领域仓储适配器） |
| `backend/.../infrastructure/cache` | Redis 权限缓存与代标记失效协议 |
| `backend/.../infrastructure/security` | 统一鉴权、功能注解拦截、全局与实例内限流、TOTP、凭据生成与哈希 |
| `backend/.../infrastructure/{config,gateway,identity}` | 数据库目标防误连守卫、Redis/Web/Identity 配置、平台网关适配器与统一身份 JWT 工具 |
| `backend/src/main/resources/db/migration` | Flyway 迁移 V1–V17，包含旧库兼容、管理员、统一身份、管理员数字主键、隔离域复合外键与 RBAC1 角色继承 |
| `frontend/src` | 控制台页面、共享组件、主题、状态与请求封装 |
| `sdk/java` | Java 业务侧 SDK（独立 Maven 工程） |
| `scripts` | 一键启停、Redis 容器管理、测试与云端检查 |
| `doc` | 中文文档与 UML 图源 |

`.local`、`output`、`target`、`dist`、`node_modules` 为忽略的工具环境、验证产物或构建产物。

## 关键约定

- 用户的当前部门是全局状态，跨软件、跨设备共享。切换部门后该用户旧部门的上下文立即失效，切回也不恢复旧 token。
- 业务软件必须在每次受保护操作前校验，不能缓存允许结果；网络错误或未知权限一律按拒绝处理。
- API Key 只保存在业务后端，不能下发到浏览器或客户端。
- Webhook 只用于提醒业务侧清理缓存或会话，接收方必须先验签；授权结论始终以在线校验为准。
- Redis 只是缓存和限流计数，可以随时清空；MySQL 是唯一数据源。
