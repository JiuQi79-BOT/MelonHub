# MelonHub 开发规范与任务交接

最后更新：2026-11-25（Asia/Shanghai）。当前工作目录：`D:\PersonalStudy\MelonHub`。本地当前分支为 `main`。`origin` 指向 GitHub 源仓库 `https://github.com/JiuQi79-BOT/MelonHub`，`gitee` 指向 `https://gitee.com/jiuqi97bot/MelonHub`；当前没有配置 `upstream` 远端。后续改动按用户要求推送到源仓库 `main` 和 Gitee `main`，不要切换到个人 Fork。

2026-11-25 验收环境使用 Docker Swarm。PR #8 合并后的代码基线为 `8bd2fcd`；为补充 RBAC1 角色层级，MelonHub 后端当前镜像为 `melonhub-backend:rbac1-d1f4d36`，前端镜像为 `melonhub-frontend:rbac1-d1f4d36-assets-fix`，采用默认 `structured` 策略；前端保留 `3c85d8a-origin-fix`。MySQL 使用 `lab_mysql` 的 `melonhub_rbac`，正式库已应用至 V17，Redis 关闭。服务器端口与更新步骤见 `deploy/README.md`，镜像、副本和实际验证范围见 `doc/验证记录.md`。本机开发是独立流程。

## 1. 用户要求与边界

2026-10-19 历史补修：全量审查保留的 15 项问题已作最小修复；用户授权新增跨页选人、多角色批量追加与同页个别调整，保留已有有效分配及期限。普通列表 CSV、软件状态筛选、角色全选/清空恢复。MFA 变更结束旧会话、完整响应超时、导出快照、Webhook 数组契约及通知、旧 401 竞态等修复见 `doc/验证记录.md` 第 1.0 节；未增加迁移。用户要求最小修改，不添加无关防御性分支或抽象。

随后按用户要求优化对象内配置流程：选定部门/软件后打开配置抽屉，角色/权限/成员授权复用原页面内容，移除三个独立侧栏入口并保留旧地址。新建部门→维护成员，接入软件→保存密钥→配置角色，新建角色→创建权限并勾选→保存并分配成员均连续完成。多部门复制、多软件配置、多软件批量授权组合现有 API，各项独立提交并保留成功结果；不增加部门与软件准入关系或模板同步。仅修改前端与说明文档，后端和迁移未动。

手机完整流程复验补修：仅改 4 个前端文件。手机日期/时间及双月份面板纵向排列、固定在屏幕内且可滚动；权限元数据列改“动作”、审计列改“事件”，避免被手机样式误当成“操作”按钮列；诊断的跨部门警告只在已有其他当前部门时显示。详情见 `doc/验证记录.md` 第 1.0.2 节。

- 用户定位：公司内部统一权限平台，为 20+ 业务软件通过 API 提供 RBAC 授权；隔离域为 部门 × 软件；切换部门使旧上下文失效。
- 用户确认的方向：企业级 RBAC 授权平台（**不做** OAuth/OIDC/IdP/SSO），前端 Ant Design Vue 4 的高密度表格控制台，高峰用 Redis 权限缓存并保证失效正确。
- 说明文档统一中文，放在 `doc/`，README 链接；保留一键启动和一键停止脚本；IDEA 可维护运行，但图形界面启动未实测，不能描述为已验证。
- 本机开发可复用已有 Docker MySQL `xmudemo-mysql`（8.3.0，卷 `xmudemo_db_data`），只使用 `melonhub_rbac` 和 `melonhub_rbac_test`。当前根目录 `.env` 的 `SPRING_PROFILES_ACTIVE=cloud`，配置的正式 schema 为 `melonhub_rbac`；只有显式切换为 `local` 才使用本机数据库配置。不得修改其他应用的数据库、数据或容器，不重建 Swarm、不删除持久化目录、不执行实验初始化 SQL。名为 backend 的 Compose 项目是 PostgreSQL，与本项目无关。
- Redis 只用本项目专用容器 `melonhub-redis`（标签 `com.melonhub.role=cache`，`127.0.0.1:6380`）。Docker 中其他 Redis 不得使用或修改；脚本对没有该标签的同名容器拒绝操作。
- 真实地址、数据库/SSH/Redis/管理员密码只在根目录忽略文件 `.env`，`.local/redis/redis.conf` 也含 Redis 密码。不要打印原文，不写进示例、文档、日志或命令行参数。Webhook 签名密钥和 TOTP 密钥以原文存在数据库中，同样不得打印或写入文档。
- 已部署课程联调使用服务器当前账号。核对课程验收登录时从根目录 `.env` 读取已验证的 `COURSE_TEST_USERS_SHARED_PASSWORD`。CSV 仅供一次性导入，导入后移出工作区。
- 中文沟通，直接推进；只读诊断和常规修复不反复询问。修改前先阅读当前文件和相关代码，尊重用户已有编辑。

## 2. 工程与目录

| 项目 | 当前情况 |
| --- | --- |
| 后端 | Spring Boot 3.5.7、Spring Data JPA（Hibernate，`ddl-auto=validate`，表结构仍由 Flyway 管理）、Spring Data Redis（Lettuce）、Bean Validation、Flyway、MySQL Connector/J、Actuator + Micrometer Prometheus、spring-security-crypto |
| Java | 源码目标 Java 17。本机 JDK 25.0.2，Swarm 后端运行 JDK 21 |
| 前端 | Vue 3.5.22、TypeScript 5.9.3、Pinia 3.0.3、Vue Router 4.5.1（hash 路由）、Vite 6.4.1、ant-design-vue 4.2.6、@ant-design/icons-vue 7、dayjs |
| Java SDK | `sdk/java`，`com.melonhub:melonhub-client:1.0.0`，独立 Maven 工程（不在根 `pom.xml` 聚合中），基于 JDK HttpClient，Spring Boot 自动装配可选；未发布到远程仓库，需 `mvn -f sdk/java/pom.xml install` |
| Maven | 根 `pom.xml` 聚合 `backend`，后端 `backend/pom.xml`；启动器可发现 PATH、MAVEN_HOME/M2_HOME 或 `.m2/wrapper/dists` |
| IDEA | `C:\Program Files\JetBrains\IntelliJ IDEA 2024.2.1`；图形启动未实测 |
| Shell | PowerShell 7；优先 rg 搜索。PowerShell 别名会遮蔽同名函数（例如 `Del`），临时脚本的函数名要避开内置别名 |

后端包 `com.melonhub.rbac`，根包只保留 `RbacApplication`（启用定时任务）。PR #8 后入口统一依赖 `service.port` 的 10 个 `*UseCase`；启动时以 `melonhub.backend-strategy=structured|ddd` 选择 `service.structured.Structured*Service` 或 `service.ddd.application.Ddd*Service`。DDD 领域模型与端口位于 `service.ddd.domain`，适配器位于 `service.ddd.infrastructure`。下文历史简称 `RbacService`、`AuthService` 等指对应职责，并非根 service 包仍有这些旧类；结构化树规则为 `StructuredAdminHierarchyProcedures`，DDD 为 `AdminHierarchy`。类图与源码对应见 `doc/后端UML与代码导读.md`。

老师课程系统的实际运行包已只读核对，当前经 `/auth/me` 获取权限；未发现 `resetCourseToApplication`、`setClassTeachers`、`listUsers` 或 `/auth/api-permissions` 调用，不因 OpenAPI 中出现 operationId 就增加映射。版本证据和静态核对限制见 `doc/课程权限接入核对.md`。

职责速查：

| 包 | 文件与职责 |
| --- | --- |
| controller | `AuthController` 健康、管理员登录、改密、两步验证和 me；`AdminController` 控制台基础配置与治理入口；`AdminRoleController` 管理员角色；`GovernanceController`（`/api/admin`）审批、复核、Webhook、清单、用户导入、权限反查、即将到期、软件限额、搜索、系统状态、审计链校验；`AccountController` 管理员账号；`IdentityController` 统一身份登录、退出、当前用户、权限与组织查询；`IdentityAdminController` 统一身份用户凭据导入；`BusinessController` 业务 API（含角色申请与只读授权查询） |
| service | `RbacService` 配置与规则，`ContextService` 上下文与校验，`AuthService` 管理员、TOTP 与 API Key 认证，`AdminRoleService` 管理员角色，`AdminTree` 管理员层级规则，`GovernanceService` 审批/复核/导入/清单/反查，`WebhookService` 事件外盒与投递，`AuditSealService` 审计哈希链封存与校验，`ConsoleService` 搜索与状态看板，`MaintenanceService` 定时清理与分配到期 |
| dao | `RbacDao`、`ContextDao`、`AuthDao`、`AdminRoleDao`、`GovernanceDao`、`IdentityDao`、`WebhookDao`、`AuditDao`：JPA（`entity` 实体、`repository` Spring Data 仓库、`Jpql` 条件拼装与结果行）；`Visibility` 负责可见范围，应用代码中不使用 JdbcTemplate 与原生 SQL。DAO 方法签名与返回结构（Map 的键、`java.sql.Timestamp` 时间）保持迁移前的样子，由 `R31_ApiContractIntegrationTest` 核对；写操作在方法返回前 flush，批量 JPQL 执行后清空持久化上下文；做决定的读取用 JPQL 投影；加锁读取先清空持久化上下文再带 `LockModeType` 查询，取到最新提交的行；带条件的更新（认领、口令步、密码替换等）保持原条件写成 JPQL；`created_at` 等由数据库生成（`@Generated`）；需要时间运算的阈值取应用服务器时钟，比较当前时间用 JPQL `current_timestamp` |
| cache | `PermissionCache`：Redis 缓存、代标记失效协议、熔断与纪元恢复 |
| dto | `Requests`：输入类型与校验 |
| security | `ApiGuard` 统一鉴权/限流/请求编号/安全头，`GlobalRateLimiter` Redis 固定窗口跨实例限流（失败回退 `RateLimiter`），`ApplicationRateLimits` 软件限额（实例缓存 30 秒），`Totp`，`AdminAccess`/`AdminPrincipal` 管理员权限，`ApplicationCaller`，`RateLimiter`，`ClientAddress`，`Credentials` |
| exception | `ApiException`、`ApiErrors`：结构化错误（含 requestId） |
| config | `CloudDatabaseConfiguration`（保留历史类名）、`RedisConfiguration`、`DemoData` |

Flyway 迁移为 V1–V17。V6、V7 保留旧版结构演进，V8 恢复部门 × 软件隔离，V9、V10 管理员功能与管理树，V11–V13 统一身份凭据与课程审计，V14 管理员数字主键，V15、V16 补齐隔离域复合外键。已应用的迁移保持原样，新增结构变更使用新版本。

Controller、Service 不直接使用 EntityManager 或仓库查询，持久化留在 DAO；新代码不写原生 SQL。不新增无意义抽象。

前端 `frontend/src`：`main.ts` 路由与权限守卫（`superOnly` 页面仅超级管理员；`domain` 标记的页面显示授权域选择），`App.vue` 布局（侧栏、面包屑、全局搜索、授权域选择、健康徽标、主题切换、账号菜单含两步验证与修改密码；手机上侧栏改为左侧抽屉、授权域移到顶栏下方整行、全局搜索收成图标），`store.ts` 身份与授权域，`api.ts` 请求与会话（token 在 sessionStorage），`theme.ts` 明暗主题与 antd 主题令牌（默认跟随系统，选择存 localStorage `melonhub-theme`，颜色需与 `style.css` 变量同步），`responsive.ts` 手机断点（≤768px，与 `style.css` 的媒体查询同步）：`isPhone`，以及给表格单元格写 `data-label`（列标题）和 `data-empty`（只有“—”或空）供手机卡片布局使用，“操作”列按标题识别，`composables.ts` 带请求序号的分页/列表（丢弃过时响应），`labels.ts` 中文标签，`types.ts` 类型。`pages/` 15 个页面：Overview、Departments、Users（含批量导入）、Applications（API Key/Webhook 抽屉，“更多”中限流）、Roles（“清单与复制”下拉：导出本部门清单/导出全部部门清单/导入清单/从其他部门复制）、Permissions（使用者反查）、Assignments（逐角色到期、统一有效天数、即将到期标签）、Approvals、Reviews、Simulate、Audit（校验链条）、Status、Accounts、AdminRoles、Integration。`components/`：DomainPicker、DomainEmpty、GlobalSearch（Ctrl/⌘+K）、ThemeToggle、PageHead、EmptyState、ReadonlyHint、LoginView（MFA 口令步骤）、MfaModal、PasswordModal、KeysDrawer、WebhooksDrawer、ReviewDrawer、PermissionHoldersDrawer、UserAccessDrawer、RoleMembersDrawer、UserPicker、SecretModal。样式在 `style.css`，覆盖 antd 默认样式时要提高选择器优先级（如 `.shell .ant-layout-header.header`），深色模式需同时检查。

对象内配置新增：`authorizationScope.ts` 提供局部授权域，默认回退全局 store；`AuthorizationDrawer` 内复用 Roles/Permissions/Assignments/Users，Users 总表异步引用该抽屉以避免模块循环初始化。`GuardedButton`/`GuardedLink` 用于需要管理功能的按钮与操作链接：无权限时置灰并以悬停提示说明缺少的功能（`hub.denial`），不要再用 `v-if` 隐藏；`PermissionEditor` 是原权限表单的共用实现；`DomainCopyModal` 逐目标调用现有复制接口；`BatchRolesModal` 逐软件调用现有批量追加接口。抽屉内切换软件保留本部门选人，切换部门清空；已有配置、有效分配与期限保留。各页面的旧 URL 仍可使用。

文档：`doc/使用说明书.md`、`API.md`、`ARCHITECTURE.md`、`本地Docker运行.md`、`验证记录.md`；SDK 文档 `sdk/java/README.md`。

## 3. 核心权限模型

RBAC1，隔离域 `(department_id, application_id)`。用户与角色、角色与权限多对多，没有用户直接权限；角色可在同一隔离域内继承父角色权限，拒绝循环和跨域继承；不引入 ABAC、职责分离、通配权限或业务规则。权限编码区分大小写，resource/action 仅为元数据。角色分配可带 `expires_at`，所有查询只计入未到期分配；到期是分配的属性，不是新规则。

**用户全局当前部门**，跨软件、跨设备共享。在另一部门创建上下文使旧部门全部软件上下文失效，切回不恢复。设备独立切换属于需求变更。

- `ContextService.issue()` 对用户行 `SELECT ... FOR UPDATE`，验证成员、用户/软件启用及激活角色为已分配角色子集；省略 activeRoleIds 为全部，空数组为无。跨部门切换增加 context_version、更新 active_department_id、清理旧版本。`switchDepartment:false` 时用户已有其他当前部门则返回 409 `DEPARTMENT_MISMATCH`，不切换；SDK 的 `hasPermission`/`token` 都这样调用，只有 `switchDepartment` 方法切换。
- 校验（`effective`/`check`）**不加锁**：先查 Redis；未命中则在只读事务中读一致性快照（`ContextDao.state`）。语义：变更提交后开始的校验一定看到新状态，与提交并发的校验可能返回旧结果。
- 失效：修改外部标识、移出当前部门、停用/删除用户、强制下线 → 上下文失效；用户在某域角色重分配、角色成员调整、批准申请、复核关闭回收 → 该域上下文失效；软件停用/删除 → 清除上下文；删除部门 → 清除前成员遗留上下文；角色停用、权限调整、清单导入 → 下一次校验生效；分配到期 → 不失效上下文，到期角色从到期时刻起不再计入（缓存结果带 `refreshAt`，见下）。
- 删除部门/软件有依赖时返回 409 并在 message 中列出剩余数量（`RbacService.remaining`）；审计对象为名称，详情“编码 xxx”。
- 写路径行锁顺序统一为“用户 → 角色 → 角色分配”。`RbacService.roleMembers` 先按 ID 顺序锁全部相关用户，再锁角色与分配；加锁读取若发现等锁期间新加入的成员，不在持有角色锁时再去锁该用户，而是回滚本次尝试，下一次把这些用户放在最前面，最多 3 次，仍冲突返回 503 `SERVICE_BUSY`。每次尝试用 `PROPAGATION_REQUIRED`（JPA 不支持保存点）：从控制器调用时没有外层事务，每次尝试是独立事务，回滚即释放全部锁；在调用方事务内（集成测试）各次尝试加入外层事务，需要重试时外层事务会被标记回滚。新建 API Key 先锁软件行（`lockApplication`），保证并发下最多 10 个有效密钥。新增写路径要遵守同一顺序。

### Redis 缓存协议（改动前必须理解）

MySQL 是唯一数据源，Redis 无持久化。缓存键带随机“代标记”：用户 `gen:u`、软件 `gen:a`、密钥 `gen:k`、域 `gen:d:{dept}:{app}`，外加全局 `epoch`。改变授权状态的写操作必须在事务内调用 `PermissionCache.touchUser/touchApplication/touchKeys/touchDomain`：提交前替换代标记并 INCR busy，提交后再替换，完成后 DECR。读取方 Lua 原子读代标记与 busy，busy 时不回写；结果键由读取时的代标记组成。结果 TTL 60 秒，且不超过结果中最早到期的角色分配（`ContextService.Payload.refreshAt`，命中时已过该时刻也重新查库）。

- 当前治理写路径的 touch：`RbacService.userRoles`（`ids` 为完整集合；`expiries` 逐角色到期、`expiresInDays` 作用于其余提交的角色，两者都未涉及的已有角色保留原期限；每次授予或改期限换新 `grant_id`）→ `touchUser`；`GovernanceService.approve` → `touchUser`；`closeReview`（只删除 `grant_id` 仍与快照相同的分配，其余条目记 `skipped`）→ 对每个被回收用户 `touchUser`；`importManifest` → 每个涉及部门 `touchDomain`。`importUsers` 只建/改用户资料并补充成员关系，不调用 touch；软件限额修改不涉及授权结果。分配到期在到期时刻没有写操作，靠 `refreshAt` 保证即时；`MaintenanceService.expireAssignments` 每 60 秒调用 `GovernanceService.expireAssignments`（每批 500）：按主键条件删除仍已到期的行（多实例只有一个删除成功），对受影响用户 `touchUser`，发 `user.roles.expired`（data 含 userId/externalId/departmentId/roleIds），审计 `USER_ROLES_EXPIRED`（actor `system`）。
- 新增任何影响授权结果的写路径时，必须调用对应 touch，并在 `PermissionCacheIntegrationTest` 补用例。touch 要求处于事务中，否则抛异常。
- 改变授权状态的写路径还应在同一事务中调用 `WebhookService.emit/emitAll`（事务外盒），事件名限 `WebhookService.EVENT_TYPES`；新增事件时同步更新该列表、前端 `labels.ts` 和 SDK `MelonHubAuthorizer.EVICT_EVENTS`（如需清缓存）。
- Redis 异常触发 5 秒熔断走 MySQL；无法标记的写记为遗漏，本实例在推进 epoch 前绕过 Redis，`recover()` 每 2 秒尝试推进。Lua 内拼接键名，只支持单节点 Redis。
- `GlobalRateLimiter` 使用 `rl:` 前缀键，有独立的 5 秒熔断（不共享 `PermissionCache` 的状态）：Redis 调用失败后 5 秒内直接用实例内令牌桶。
- `CACHE_ENABLED=false` 可完全关闭缓存（全局限流随之退回实例内）。

### 管理员与凭据

控制台权限按功能配置：19 项功能（`AdminFunction` 枚举，与 `admin_functions` 表一致），每个 `/api/admin` 处理函数用 `@RequiresFunction` 声明所属功能，由 `AdminFunctionGuard` 拦截校验，未声明的接口一律拒绝；管理员角色（`admin_roles`）是一组功能，内置 SUPER_ADMIN / ADMIN / APP_ADMIN / DEPARTMENT_ADMIN / AUDITOR 不可修改且保持原有权限（`R18_AdminFunctionGuardIntegrationTest` 核对功能声明），可在控制台新建自定义角色（只能授予自己拥有的功能）。软件范围属于账号（`all_applications` + `admin_account_applications`），处理函数用 `AdminAccess.application` 校验；列表按可见软件过滤。角色的功能每次请求重新读取，修改即生效。管理员按部门分级：账号组成树（`AdminTree`：根管理全部账号，其余管理子树；下级的角色功能、部门与软件范围为上级子集；`DEPARTMENT` 部门管理员是叶子、必须限定部门、不能有账号/角色管理功能）。账号与角色的修改先锁全部账号行（`lockAccounts`），在内存中校验修改后的树，通过后才写入。部门范围经 `Visibility`（软件、部门，null 表示不限）传入列表查询，授权域接口用 `AdminAccess.domain` 同时校验部门与软件；平台级功能（`AdminFunction.platformWide`）与用户批量导入要求全部部门。`.env` 的 ADMIN_* 只在账号不存在时创建初始超级管理员；`ADMIN_PASSWORD_RESET=true` 重启一次可恢复，之后改回 false。5 次失败（密码或动态口令）锁 15 分钟，会话 8 小时、空闲 30 分钟；保留至少一个启用的超级管理员。

两步验证：`Totp` RFC 6238（SHA1、30 秒、6 位，容忍 ±1 步）；setup 生成待绑定密钥，confirm 后生效；登录记录 `mfa_last_step`，同一步不能复用（停用两步验证同样检查）；错误码 `MFA_REQUIRED`/`MFA_INVALID`；停用需密码 + 口令；超级管理员 `POST /api/admin/accounts/{id}/mfa/reset`。

上下文 1 小时；API Key、上下文 token、会话 token 只存 SHA-256；管理员密码 BCrypt cost 12，12–100 个字符且 UTF-8 不超过 72 字节（超出拒绝，不截断；`.env` 初始密码超出则拒绝启动）。会话绑定 `credential_version`：改密、重置、改角色、停用、两步验证变更都递增版本；登录用条件插入创建会话，要求账号仍启用且版本、密码哈希、TOTP 密钥与校验时一致，否则 401 `BAD_CREDENTIALS`，因此并发改动或删除后同名重建都不会留下可用会话。账号名可含 `@`、`.`，不能以点开头。每软件最多 10 个有效 API Key（`application_keys`），可设有效期、单独吊销；`rotate-key` 是紧急轮换，吊销全部旧密钥。API Key 不下发浏览器；业务后端先认证用户再提交可信 externalId；平台不撤回已执行的外部业务操作。

Webhook：头 `MelonHub-Event`、`MelonHub-Delivery`、`MelonHub-Signature: t=<秒>,v1=hex(HMAC-SHA256(secret,"{t}.{body}"))`；每 5 秒认领最多 20 条（不超过发送线程池空余队列），交给 `melonhub.webhooks.concurrency`（默认 4）个发送线程并行推送，调度线程不等 HTTP；认领与写回都以 `attempts` 为条件，租约过期后旧线程不能覆盖新结果；停用的 Webhook 不投递但保留队列；超时 5 秒，不跟随重定向，失败退避 min(60,2^(n-1)) 分钟，共 8 次后 FAILED；`SENDING` 租约 5 分钟。SDK 验签默认容差 300 秒。URL 只校验 http(s) 格式，未做目标网段限制。

审计哈希链：`AuditSealService` 每 10 秒锁 `audit_seal` 单行，按封存顺序分配 `seal_seq`（晚提交的小 ID 记录接在链尾），`chain_hash = SHA-256(prev_hash | 规范化记录)`；`seal_seq >= v2_from_seq` 的记录用 v2 形式（序号开头、每字段“长度:值”），更早的保持原形式。`audit_seal` 记链尾 `last_seq/last_hash` 与锚点 `anchor_seq/anchor_hash`；保留期清理按链前缀删除并更新锚点。`/api/admin/audit/verify?fromSeq=&limit=`（limit ≤ 50000）返回 `complete/nextSeq` 与第一处 `problem`：`LINK_BROKEN`、`MODIFIED`、`TAIL_MISSING`。链与数据同库，不是外部存证。

`ApiGuard` 默认拒绝，只有 `/api/health`、`/api/auth/login` 公开；路径必须匹配规范形式，否则 400 INVALID_PATH（防 `;`、`%` 编码绕过）。不要放宽该正则。登录限流每来源地址每分钟 10 次，业务 API 按软件每秒限额，均经 `GlobalRateLimiter`。

## 4. 配置与启停

服务器验收直接访问 Swarm 前端 `5174`。`deploy/stack.yml` 与服务器部署目录私有 `.env` 管理镜像和运行配置，变更后重新生成 `stack-resolved.yml`。只发布前端时更新 `melonhub_melonhub-frontend`。Nginx 需要转发对外的主机和端口。发布后经 `5174` 验证带浏览器同源 `Origin` 的登录与 `/api/health`，并按 `X-Request-Id` 检查后端日志。详见 `deploy/README.md`。

`application.yml` 从根目录或 backend 上一级加载 `.env`，缺省 profile 为 cloud；当前根目录 `.env` 使用 `SPRING_PROFILES_ACTIVE=cloud` 和 `CLOUD_DB_NAME=melonhub_rbac`。本机开发 profile 使用 `LOCAL_DB_*`，测试必须指向专用 `melonhub_rbac_test`。Redis：`CACHE_ENABLED`、`REDIS_HOST/PORT/PASSWORD/DATABASE/CONTAINER`。可选调优：`APP_RATE_LIMIT`（默认 1000，0 不限）、`GLOBAL_RATE_LIMIT`（默认 true）、`WEBHOOK_DELIVERY_ENABLED`（默认 true）、`WEBHOOK_RETENTION_DAYS`（默认 30）、`DB_POOL_SIZE`（20）、`AUDIT_RETENTION_DAYS`（180）、`ADMIN_IDLE_MINUTES`（30）、`MANAGEMENT_PORT`（8081）、`HTTP_MAX_THREADS`（200）。仅在 `application.yml` 中固定、没有环境变量的项：`melonhub.webhooks.timeout-millis`（5000）、`melonhub.webhooks.max-attempts`（8）、`melonhub.webhooks.concurrency`（4）、`melonhub.rate-limit.login-per-minute`（10）、`melonhub.maintenance.enabled`（true，同时控制定时清理和审计封存）；TOTP issuer 固定为 `MelonHub`（代码中）。`.env` 中不要留 `KEY=` 空值，Spring 会当作空字符串。

`service-tools.ps1` 的 `Import-MelonRuntimeConfiguration` 对空值删除环境变量而不是设为空串（空 `SERVER_ADDRESS` 曾导致监听全部网卡）。新增配置项时同时加入该列表和 `.env.example`。

`CloudDatabaseConfiguration` 在 Flyway 前验证入口、端口、固定 schema、TLS 与 JDBC 参数，local 仅允许 loopback；`CloudTestDatabaseGuard` 保护测试库。JDBC 使用 `serverTimezone=SERVER`；JSON 时区 Asia/Shanghai。

```powershell
./scripts/start-all.ps1            # 拉起 MySQL 容器、创建/拉起 Redis 容器、按需构建、启动前后端
./scripts/start-all.ps1 -NoBrowser
./scripts/start-all.ps1 -Rebuild
./scripts/stop-all.ps1             # 停止本项目 Java/Vite 与带标签的 Redis，保留共享 MySQL
```

前端固定 5174（5173 留给其他项目），后端 8080，监控 8081，均只绑定 127.0.0.1；端口被占用时拒绝启动。启停持有 `.local/services.lock`，地址写 `.local/services.json`，日志在 `.local/logs/`（脱敏）。修改配置/源码后先停再启。

前端依赖用 `npm ci --ignore-scripts`：本机 PATH 超过 8191 字符时 cmd.exe 运行的生命周期脚本找不到 node。手动 `npm run build` 同样受影响，需在当前终端缩短 PATH（见 `doc/本地Docker运行.md`）。

`.run/` 含 Backend、Frontend、Stack、Tests 配置。

## 5. 验证证据与当前状态

- 当前与历史验证以 `doc/验证记录.md` 为准。四个验收前后端的 Swarm 副本均为 `1/1`，2026-11-24 已通过 `5174` 验证 MelonHub 登录、当前会话及三个菜单请求的后端日志，并经 `18090` 验证课程教师和学生登录及权限查询。
- Redis 相关集成测试使用专用缓存环境。R31 本机曾遇到系统状态接口事务超时，此前的 Maven 报告不作为当前全绿证据。
- 历史验证工具、截图、旧部署配置和导入输入归档在工作区外。`.local` 用于当前开发工具和本次运行产物。环境准则与发布入口以仓库说明及服务器当前部署配置为准。
- 云端数据库查询只用已审核的只读工具。共享 MySQL 的账号、数据、容器和 Swarm 拓扑由其维护流程管理。

## 6. 差距分析与后续路线

已补齐：分级管理员与登录防护、两步验证、API Key 生命周期、审计详情与导出、审计哈希链、授权诊断、角色成员视图、配置复制、权限缓存与高峰保护、跨实例全局限流与按软件限额、健康与指标、Java SDK、Webhook 变更通知、用户批量导入与权限清单、授权治理（分配有效期、申请审批、定期复核）。

仍未做（按建议优先级）：

1. 高可用部署：Redis 主从 + 哨兵、HTTPS 反向代理与CI 流水线（含 Playwright 端到端用例）。
2. 业务侧 SDK：Node、Go 版本（Java 已完成）。
3. 审计外部存证：导出到 SIEM/WORM 存储（当前哈希链与数据同库）。
4. 用户同步：HR/SCIM 自动同步（当前为手工批量导入）。
5. 授权治理深化：审批人路由（角色负责人/多级审批，当前任何有该软件写权限的管理员都可审批）、按周期自动发起复核、审批/复核待办的邮件或 IM 提醒、清单导入前差异预览（dry-run）、授权配置版本与回滚。
6. 管理面：可脚本化的管理 API 令牌、管理员会话列表与下线、密码有效期/历史策略、控制台来源 IP 白名单。
7. 小改进：Webhook 目标地址白名单、Webhook/TOTP 密钥加密存储、TOTP issuer 可配置；Grafana 面板与告警规则样例。
8. 控制台：国际化、按需拆分 antd 体积。企业 SSO 明确不在范围内；职责分离和 ABAC 不在当前 RBAC1 范围内。

## 7. 已知限制

- Flyway 对 MySQL 8.3 与 9.2 均提示超出已测试版本范围；本项目迁移和用例实际通过，升级依赖时需再验证。
- 缓存要求单节点 Redis。限流默认经 Redis 跨实例共享（1 秒固定窗口，窗口边界可能出现短时双倍）；Redis 不可用、`CACHE_ENABLED=false` 或 `GLOBAL_RATE_LIMIT=false` 时退回按实例令牌桶。多实例时定时任务在每个实例运行（删除幂等，审计封存靠行锁，Webhook 投递靠条件认领）。
- `user.roles.expired` 由每分钟一次的任务发出，最多晚约 1 分钟；校验结果在到期时刻即生效。
- 压测数字来自上一轮 Docker Desktop 单机，不是生产容量承诺；本轮未重测。
- IDEA 图形运行按钮尚未实测。验收入口和最新部署记录见 `deploy/README.md` 与 `doc/验证记录.md`。
- `.env` 按 Java properties 与 PowerShell KEY=value 读取，特殊字符和引号需验证双方一致，不得盲目给密码加引号。
- 默认忽略 .env/.env.*（仅放行 .env.example）、.local、output、target、dist、node_modules、日志。不得把真实配置复制到其他共享文件。
