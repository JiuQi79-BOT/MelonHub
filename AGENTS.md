# MelonHub 当前开发规范

更新日期：2026-10-27。工作目录：`D:/PersonalStudy/MelonHub`。本文件仅记录当前有效约定；旧模型、旧命令及历史验收见 [历史开发交接](docs/历史开发交接.md)，不得当作当前运行方式。

## 沟通与工程

- 中文沟通，直接推进；只读诊断和常规修复不反复询问。修改前阅读当前文件及相关代码，保留用户已有编辑。最小修改，不增加无关防御分支、抽象或重构。
- 说明文档统一中文，位于 `docs/`，由 README 导航。当前分支为 `main`；实际远端为 `gitee`（Gitee）和 `origin`（GitHub 原仓库），推送前核对目标和差异，仅按用户授权推送，不沿用历史 fork/PR 说明。
- `.codeartsdoer/` 等工具状态及其他无关改动不得混入任务提交；不擅自撤销或删除用户文件。
- 后端 Spring Boot 3.5.7、Spring JDBC、Flyway，源码目标 Java 17；服务器运行和本机覆盖率验证使用 JDK 21。根 Maven 仅聚合 backend，Java SDK 独立构建。
- Controller 处理 HTTP 和访问范围，Service 处理规则与事务，DAO 放 SQL、JdbcTemplate 和锁；Controller/Service 不直接使用 JdbcTemplate，不新增空转层。
- 前端 Vue 3、TypeScript、Ant Design Vue 4，保持现有高密度控制台风格，兼顾手机与明暗主题。局部管理范围复用 `authorizationScope.ts`；响应式断点 768px；主题变更同步 `theme.ts` 和 `style.css`，覆盖 antd 时遵循现有优先级。
- 异步列表、搜索、抽屉和会话继续丢弃过时响应。职责与流程见 [详细设计](docs/详细设计.md)、[项目讲解与数据链路](docs/项目讲解与数据链路.md)；UML 图和可重建源文件位于 `docs/UML/`。

## 启停与部署

- 后端已部署为服务器独立容器 `melonhub-backend`，标签 `com.melonhub.role=backend`，配置和运行包在 `/opt/melonhub/`，服务器回环 API 为 18080，重启策略 `unless-stopped`。
- 当前 `BACKEND_MODE=server`：`start-all` 只启动本机 Vite（回环 5174）和 SSH API 通道（回环 8080）；`stop-all` 不停止服务器后端。服务器模式不支持 `start-all -Rebuild`。
- `BACKEND_MODE=local` 仅表示本机 Java 调试，仍连接云 MySQL，不等于已移除的 Spring `local` profile。本机 Java API/监控为回环 8080/8081；只有该模式支持停止后 `-Rebuild`。
- 后端更新先测试，再执行 `scripts/deploy-backend.ps1`；部署脚本构建时跳过测试。`scripts/server-backend.ps1 status/start/stop/restart` 管理服务器后端。部署只操作有项目标签的容器；数据库迁移不会随容器回滚。
- Docker Hub 不通，当前复用项目专用 `melonhub-runtime:21`，由忽略配置 `BACKEND_IMAGE` 选择。服务器工具依赖 `.local/cloud-tools` 的 Paramiko，依赖见 `scripts/requirements-server.txt`；SSH 主机密钥沿用 `.local/cloud_known_hosts`，不得删除以绕过密钥变化。
- 启停脚本不管理 MySQL/Redis 容器，不修改其他应用、共享 Docker、Swarm 或持久化目录，不执行实验初始化 SQL，不重置历史账号密码。参考 PDF 不是执行授权。
- 端口占用时拒绝启动，不结束其他程序。启停锁、状态及日志位于 `.local/`；修改本机运行配置后先停再启。完整操作见 [服务器部署与启动](docs/服务器部署与启动.md)、[云数据库运行](docs/云数据库运行.md)。IDEA 配置在 `.run/`，图形运行按钮未实测。

## 数据与授权

- MySQL 是唯一持久数据源，只用云正式库 `melonhub_rbac`、独立测试库 `melonhub_rbac_test`，入口、固定 schema 与 TLS 由启动保护校验，不回退本地数据库。
- 当前 V7 为软件级 RBAC0：角色和权限分别归属软件，编码各自在软件内唯一；`role_permissions` 多对多共享权限，复合外键拒绝跨软件关联。
- 修改共享权限影响所有引用角色；删除某角色或移除其关联不删除其他角色授权。权限编码区分大小写，resource/action 仅为元数据。
- 部门仅组织成员、筛选与批量设置软件角色。无部门用户可以申请、获批和创建上下文；部门变化不撤销软件角色或上下文。跨部门配置复制已取消。
- 无用户直接权限、角色继承、ABAC、通配规则、职责分离或业务规则；不做 OAuth/OIDC/IdP/SSO。
- 分配保留 `expires_at` 和 `grant_id`；到期即不计入权限，无需等待每分钟清理。复核只回收快照中的同一次分配，续期或重新授予后跳过旧快照。
- 用户角色 PUT 的 `ids` 是整体替换，空数组清空；批量追加保留已有有效分配和期限。权限清单是软件级 v3，权限无 roleCode，旧 v1/v2 拒绝直接导入。
- 已应用的 V1–V7 不修改，未来结构变更新增 V8 或后续迁移。V7 完整归档重复组、保留最小 ID 权限元数据及用户分配期限，不合并角色。详见 [数据库设计](docs/数据库设计.md)。
- 写路径锁顺序为用户、角色、分配；批量按 ID 排序。角色成员替换遇新成员使用现有回滚重试，最多三次后返回 503；保存点回滚不保证释放外层 InnoDB 锁。新建 API Key 先锁软件行，维持每软件最多十个有效密钥。

## 缓存与安全

- Redis 默认关闭，服务器 Redis 尚待部署验收。关闭或故障时回退 MySQL/实例内限流，不能声称跨实例限流仍生效。MySQL 校验不加行锁，正常协议下提交后开始的校验看到新状态，并发重叠请求可能看到旧结果。
- 改缓存前阅读 [架构说明](docs/ARCHITECTURE.md) 的代标记、busy、epoch 协议。影响授权的写操作在事务内调用对应 `touchUser/touchApplication/touchKeys`；软件配置与清单导入触发软件代失效。历史域代机制仍在代码中，不代表部门隔离。
- 结果 TTL 最长 60 秒且不超过最早分配到期时刻，命中也检查 `refreshAt`。Lua 仅支持单节点 Redis；缓存和全局限流各有独立五秒熔断。部分网络故障下其他实例可能在 epoch 恢复前读到最长 TTL 内的旧结果，不作绝对实时承诺。
- 新增授权写路径须补缓存集成用例，并在同一事务中发出对应 Webhook 外盒事件；新增事件同步 `WebhookService.EVENT_TYPES`、前端 `labels.ts` 和需要清缓存的 SDK `EVICT_EVENTS`。
- `ApiGuard` 默认拒绝，仅健康和登录公开；不放宽规范路径校验。业务 API 按软件 ID 限流，同软件多个 API Key 共享额度。管理接口逐一校验 `AdminAccess`，搜索与报表遵守管理员可见软件范围。
- API Key 只给可信业务后端；业务后端先认证真实用户再提交 externalId。平台只判断授权，不负责业务用户登录，也不撤回已执行的外部操作。每次受保护操作前在线校验，网络错误按拒绝处理。
- 管理员四类角色及软件范围遵循现有实现，保留至少一个启用的超级管理员。会话最长八小时、空闲三十分钟，五次失败锁十五分钟；密码 BCrypt cost 12，12–100 字符且 UTF-8 不超过 72 字节。
- 会话绑定凭据版本：自行改密保留当前会话、结束其他会话；重置密码、修改角色、停用账号或启用/停用/重置 MFA 结束全部会话。关闭标签页不等于服务端注销。TOTP 使用现有 RFC 6238 及时间步防重放规则。
- 上下文一小时；API Key、上下文 token、管理员 token 仅存 SHA-256。Webhook/TOTP 密钥因参与计算在数据库中仍为原文，不得输出。Webhook 沿用 HMAC-SHA256、事务外盒、条件认领及重试；审计链与数据同库，不代替外部存证。
- 真实地址和数据库/SSH/Redis/管理员密码只存忽略 `.env`，不打印、不提交、不放入命令参数或示例；`.local/`、`output/`、target、dist、node_modules 和日志不提交。
- `.env` 按 properties 与 PowerShell KEY=value 读取，不盲目加引号或保留可选空值。新增运行配置同步 `Import-MelonRuntimeConfiguration` 和 `.env.example`，不让空值覆盖安全默认值。接口契约见 [API](docs/API.md)。

## 验证与交付

- 后端：`./scripts/test-backend.ps1`；覆盖率：`./scripts/test-backend.ps1 -Coverage`。仅用云测试库，不与其他进程并发共用；云连接参数见 [JaCoCo 测试指南](docs/JaCoCo测试指南.md)。
- 前端：`npm --prefix frontend test`、`npm --prefix frontend run build`；安装用 `npm ci --ignore-scripts`。Windows PATH 过长时在当前终端缩短，避免 cmd.exe 的 8191 字符限制。
- SDK：`mvn -f sdk/java/pom.xml -B -ntp test`；服务器工具测试使用 `.local/cloud-tools` 环境和 `scripts/tests/`。按风险验证，不把历史结果当作本次通过。
- 浏览器使用 `.local/` 下独立临时配置，禁用同步与扩展，不用默认 Edge 账号；结束后清理。不为验证停共享数据库、重启 Docker Desktop 或修改其他应用数据。
- 结果、跳过项及未验证范围写入 [验证记录](docs/验证记录.md)。部署前通过相关测试，部署后检查项目容器、运行包校验值和数据库/API 健康。
