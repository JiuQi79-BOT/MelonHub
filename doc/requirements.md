# MelonHub 统一权限平台 · 需求文档 v2.0

> 术语含义一律以根目录 [CONTEXT.md](../CONTEXT.md) 为准。  
> 编写原则：仿照联调示例项目 `CourseProjectMgt` 的需求规格与测试对照规范，整合早期《统一权限系统软件需求说明书（1.0 草案）》与项目全周期迭代记录；**凡与现行代码（提交 `8bd2fcd`，Flyway `V1–V17`）存在差异的条款，一律以现行代码实现为准**，差异原因集中记录在第 6 节。
> 需求与现有测试代码按本文件编号 `R1–R34` 建立可定位的对照（见第 5 节）。

---

## 1. 系统定位与范围

**做什么**：MelonHub 是企业级统一权限授权平台，同时满足两类真实业务接入场景：

1. **多业务软件在线 RBAC1 授权底座（`/api/v1/**` + Java SDK）**：为 20 个以上内部业务软件提供统一的角色/权限动态配置、权限上下文签发、跨部门切换即时失效、无锁在线权限校验、申请审批、定期权限复核、Webhook 变更通知与防篡改审计哈希链。
2. **统一身份认证与权限联调服务（`/auth/**`）**：面向课程项目管理系统（`course-project-management`）等接入方，提供基于 JWT 与会话索引的统一登录/退出、实时角色与前端菜单/按钮权限查询、基于 OpenAPI `operationId` 的后端全局 API 权限校验、组织部门树/成员查询，以及按授权部门隔离的课程业务审计日志查询。

**核心模型与边界**：

- **RBAC1 角色授权**：用户与角色多对多、角色与权限多对多；同一隔离域内角色可以继承父角色权限，没有用户直接权限、ABAC 规则、职责分离或通配符权限。
- **隔离域 = 部门 × 软件**：角色、权限和授权均严格隔离在 `(department, application)` 二维域内，并由数据库复合外键物理兜底。
- **全局当前部门**：用户的当前部门跨软件、跨设备共享；切换部门时原部门下全部软件的权限上下文立即失效，切回不恢复旧上下文。
- **全局权限 vs 对象级权限分离**：MelonHub 只判定全局角色与权限编码；接入业务软件自身的业务流程、页面渲染、对象级归属校验（如某教师是否任教某教学班、某学生是否属于某小组）均由业务软件自身完成。
- **单一事实源**：MySQL 是唯一持久化数据源；Redis 仅作可随时清空的权限缓存与跨实例限流计数，故障或关闭时自动回退 MySQL。

**明确不做（非目标）**：

- 不实现 OAuth 2.0 / OIDC / SAML 标准 IdP 或跨系统单点登录协议。
- 不实现外部业务系统内部流程（如 OA 审批流、HR 考勤薪资、课程/教学班/成绩管理等）。
- 不为业务软件自动生成菜单树或强制限定权限粒度为 URL + HTTP 方法（权限编码语义由接入软件解释）。
- 不撤回业务软件已经执行的外部业务操作。

---

## 2. 参与者、角色与控制台界面结构

### 2.1 参与者与角色

| 角色 / 参与者 | 身份来源 | 职责与权限边界 |
| --- | --- | --- |
| **超级管理员（`SUPER_ADMIN`）** | 平台内置角色（`MANAGER` 类型） | 拥有全部 **19 项**管理功能；初始超级管理员由环境配置引导创建；顶级账号管理全部管理员账号，负责管理员账号与角色治理、解锁与重置两步验证。平台至少保留 1 个启用超级管理员。 |
| **平台管理员（`ADMIN`）** | 平台内置角色（`MANAGER` 类型） | 拥有除 `account.manage`、`role.manage` 外的 **17 项**功能，负责维护部门、用户及其统一身份登录凭据、接入软件、API Key、Webhook 与授权治理。 |
| **软件管理员（`APP_ADMIN`）** | 平台内置角色（`MANAGER` 类型） | 按「全部部门 + 指定软件」划定范围，跨部门管理所授软件的角色、权限、用户授权、审批、复核、清单、API Key 与 Webhook；部门与用户只读。 |
| **部门管理员（`DEPARTMENT_ADMIN`）** | 平台内置角色（`DEPARTMENT` 叶子类型） | 按「指定部门 + 全部软件」划定范围，不能设下级管理员，不能持有 `account.manage` / `role.manage`；可启停和强制下线完全属于所管部门的用户，并管理这些部门下全部软件的授权。 |
| **审计员（`AUDITOR`）** | 平台内置角色 | 仅拥有 5 项只读功能（`org.view`、`domain.view`、`governance.view`、`audit.view`、`system.view`），可浏览、导出审计并校验哈希链，不能执行任何写操作。 |
| **自定义角色管理员** | 管理员在「管理员角色」页动态创建 | 从创建者自己拥有的功能中挑选子集组合（但不可获授仅限超级/平台管理员的 `user.manage`、`user.credentials`），账号可同时限定部门与软件范围。 |
| **接入业务软件（通用 API Key 模式）** | 软件管理生成的 `X-API-Key` | 业务后端先自行认证终端用户，再以可信 `externalId` 调用 `/api/v1/**` 签发/校验权限上下文、提交角色申请、接收 Webhook 通知。 |
| **统一身份用户与课程系统（`/auth/*` 模式）** | `identity_credentials` 凭据 + `Bearer JWT` | 课程系统经 `/auth/login` 获取 JWT，后端通过 `/auth/me` 获取角色与权限；MelonHub 同时提供菜单/操作权限、组织和课程日志查询能力，实际调用范围见专项核对。 |

### 2.2 控制台界面结构（导航层级与对象内配置）

管理控制台采用 Vue 3 + TypeScript + Ant Design Vue 4 构建，支持 PC 端与手机端（≤ 768px 左侧抽屉导航 + 表格卡片化）自适应与明暗主题切换（跟随系统，偏好存 `localStorage`）：

| 分组 | 侧栏入口（共 12 项；保留 15 个可直达路由） | 说明 |
| --- | --- | --- |
| 概览 | 概览（`/overview`） | 平台核心统计、当前授权域概览 |
| 组织与软件 | 部门（`/departments`）、用户（`/users`）、接入软件（`/applications`） | 选定部门或软件后在**对象内配置抽屉**连续完成成员、角色、权限、批量授权、API Key 与 Webhook 维护；角色/权限/用户授权原独立路由仍保留直达能力 |
| 授权治理 | 审批中心（`/approvals`）、权限复核（`/reviews`）、权限诊断（`/simulate`） | 角色申请审批、定期复核快照回收、不切部门试算有效权限 |
| 监控与运维 | 审计日志（`/audit`）、系统状态（`/status`） | 审计检索/导出/哈希链校验；数据库、Redis、待办与到期汇总 |
| 平台管理 | 管理员账号（`/accounts`）、管理员角色（`/admin-roles`）、接入指南（`/integration`） | 分级管理员树（数字主键 ID）、自定义角色功能配置、接口与 SDK 接入指引 |

- **权限反馈规范**：按钮与操作链接统一通过 `GuardedButton` / `GuardedLink` 渲染；无对应管理功能或超出部门/软件范围时**置灰并悬停提示明确原因**（`denial()`），且服务端拦截器独立兜底，绕过前端仍返回 403。
- **会话存储**：控制台会话 Token 仅存于浏览器 `sessionStorage`（关闭标签页失效），不写入 `localStorage`；支持 `Ctrl/⌘+K` 全局搜索。

---

## 3. 功能需求与验收标准

### A. 组织、用户与接入软件

**R1 部门管理与依赖保护**
平台管理员可在全部部门范围内新建、修改、删除部门，并支持父子部门层级（`parent_id`）用于组织树查询；部门编码全局唯一（区分大小写）。

- 部门若仍关联成员、角色、权限或被用作某个管理员账号的部门范围限制，禁止直接删除，返回 `409` 并在错误消息中列出剩余依赖数量。
- 验收：新建/修改部门即时生效；存在成员、授权或受限管理员引用时删除被拒绝并明确告知剩余对象；无依赖部门可正常删除并清理前成员遗留上下文。

**R2 用户资料、多部门归属与账号生命周期**
系统维护用户最小资料（姓名、全局唯一外部标识 `externalId`、可选邮箱、启用状态及多部门成员关系），支持分页、关键词、部门与启用状态筛选及全量筛选 CSV 导出。

- 新用户默认无任何角色与权限；加入部门不自动继承该部门任何权限。
- **修改外部标识**：修改 `externalId` 必须立即使该用户全部既有权限上下文失效，同步更新其统一身份登录名并撤销全部统一身份 JWT 会话；仅修改姓名或邮箱不影响既有上下文。
- **移出部门**：将用户移出某部门时，两套后端实现（`StructuredRbacService.user()` 与 `DddRbacService.user()` + `UserMembershipAggregate`）均显式查询并批量删除该用户在被移出部门下的角色分配（`RbacDao.removeUserRolesInDepartments()`），按受影响的 `(departmentId, applicationId)` 逐域吊销既有上下文、广播 `user.roles.changed` Webhook 事件并记录 `USER_ROLES_UPDATED` 审计明细，杜绝遗留脏授权。
- **停用、强制下线与删除**：停用用户使全部权限上下文和统一身份会话立即失效，重新启用也不恢复旧上下文；支持「强制下线」撤销全部上下文；永久删除需独立 `user.delete` 功能。部门受限管理员仅能停用/删除/强制下线完全属于自己所管部门的用户。
- 验收：分页检索与导出符合可见部门范围；改 `externalId`、移出部门、停用、强制下线均立即阻断旧凭证；重新启用后旧上下文仍返回 `CONTEXT_INVALID`。

**R2a 用户批量导入**
管理全部部门且具备 `user.manage` 功能的超级/平台管理员可一次导入最多 500 行用户 JSON 数据。

- 按 `externalId` 幂等创建或更新用户，按部门编码补齐成员关系，不移除已有成员关系，不自动授予任何角色，不覆盖已有用户的 `externalId`。
- 逐行独立校验：某行缺少必填项、长度超限或部门编码不存在仅令该行失败并报告行号与原因，不中断其余合法行的导入。
- 验收：批量导入混合合法与非法行时，合法行成功落库、非法行在响应中精确列出失败行号与原因。

**R3 接入软件生命周期管理**
具备 `application.manage` 功能且管理全部部门的管理员可登记、修改、启停和删除业务软件，软件编码全局唯一（区分大小写），接入新软件无需修改平台代码。

- 停用软件时立即吊销其全部在线权限上下文，且其所有 API Key 立即失效、不可继续调用业务接口。
- 删除仍存在角色、权限、授权或待治理记录的软件返回 `409` 并列出剩余数量。
- 验收：软件停用后原 API Key 与上下文均立即被拒；有依赖的软件拒绝删除并说明剩余依赖。

**R4 软件多 API Key 管理与轮换**
每个接入软件支持同时持有最多 10 个有效 API Key（通过锁软件行防止并发超限），支持可选有效期、单独吊销和紧急轮换（`rotate-key`，一次吊销全部旧密钥并签发新密钥）。

- API Key 明文仅在创建/轮换时展示一次，数据库仅存 SHA-256 哈希，且异步批量刷新最近使用时间。
- API Key 严格绑定所属软件，不能读写、授权或校验其他软件的隔离域数据；控制台凭证与业务 API Key 严格分离、不得混用。
- 验收：多密钥各自按有效期与吊销状态独立生效；紧急轮换后旧密钥立即 401；持 A 软件密钥操作 B 软件域返回拒绝。

---

### B. 隔离域 RBAC1 角色与权限配置

**R5 隔离域（部门 × 软件）物理与逻辑隔离**
所有业务角色、权限项及用户角色分配均归属于唯一 `(departmentId, applicationId)` 隔离域，角色编码与权限编码在域内唯一且严格区分大小写（`utf8mb4_bin`）。

- 跨部门或跨软件将角色与异域权限关联、或将异域角色分配给用户时，应用层原子拒绝，且数据库复合外键 `fk_role_permissions_role_domain`、`fk_role_permissions_permission_domain`、`fk_user_roles_role_domain`（均已在 `V15`/`V16` 对新库与旧库升级路径幂等补全）及 `ck_user_roles_department_not_null` 约束构成物理级防线。
- 业务 API 的角色/权限 CRUD 仅限操作当前密钥所属软件及请求指定隔离域。
- 验收：同编码角色/权限在不同隔离域互不干扰；应用层与原生 SQL 跨域关联均被拒绝。

**R6 角色、权限动态定义与角色成员维护**
具备 `domain.configure` 的管理员可在授权域内动态增删改角色、启停角色、增删改权限项及配置角色权限关联；具备 `domain.assign` 的管理员可从角色侧批量维护角色成员列表。

- 角色停用或移除角色中的某项权限后，该变化在用户的下一次权限校验中立即生效（无需等待缓存过期）。
- **并发防死锁**：所有写路径统一按「用户（ID 升序）→ 角色 → 角色分配」顺序加锁；角色成员替换若在等锁间隙发现晚到新成员，主动回滚本轮尝试释放全部锁并将晚到成员前置重试（最多 3 次，仍冲突返回 `503 SERVICE_BUSY`）。
- 角色成员新增同时记录授予人与授予时间。
- 验收：撤除角色权限后既有上下文立即失去该权限；并发成员变更与角色成员替换不死锁且状态一致。

**R7 用户角色分配、逐角色有效期与批量追加**
具备 `domain.assign` 的管理员可为部门成员在当前隔离域分配角色，支持整体替换与跨页多选批量追加两种模式。

- **三层到期优先级**：逐角色 `expiries`（传 `null` 为永久）> 统一 `expiresInDays` > 未提及的已有角色保留原到期时间与原 `grantId`；新增角色未指定期限时默认永久。
- 每次新授予或修改期限均生成全新 `grantId`（UUID）；过期表单提交不能把已被清理的限时授权意外复活为永久授权。
- 批量追加仅允许为当前部门成员授予当前隔离域内的角色，并保留已有有效分配及其期限；软件受限管理员只能向可管软件执行批量追加。
- 重分配用户角色仅作废该用户在当前隔离域的权限上下文，不影响其在同一部门下其他软件的上下文。
- 验收：逐角色到期、保留原期限、防过期表单复活、批量追加与作用域隔离均符合规则。

**R8 跨部门复制授权配置与权限清单导入导出**
具备 `domain.transfer` 的管理员可在同一软件下将源部门的角色、权限与关联关系复制到目标部门，也可导出单部门或全部部门的权限清单 JSON 并幂等导入。

- 跨部门复制跳过目标部门已存在的角色/权限编码，并可按需复制角色权限绑定；源、目标部门均须在操作者可见部门范围内。
- 清单导入仅允许包含操作者可管理的部门；导入导致角色停用或重授时同步广播相应 Webhook 事件并失效域缓存。
- 验收：复制与导入导出往返（Round-trip）保持配置一致，已存在编码不重复报错，变更事件正常发出。

---

### C. 在线权限上下文、部门切换与鉴权

**R9 权限上下文签发与全局当前部门切换**
业务软件调用 `POST /api/v1/contexts` 为已认证用户签发权限上下文（有效期 1 小时，库中仅存令牌 SHA-256 哈希；支持 `CloneFactory` 强类型契约转换）。

- 用户必须已启用且属于目标部门，软件必须已启用；非成员不能切换或创建上下文。
- 激活角色集合 `activeRoleIds` 必须是用户在该域已分配未到期角色的子集（省略表示全部已分配角色，空数组表示不激活任何角色）。
- **部门切换控制**：默认或 `switchDepartment: true` 时，若目标部门不同于用户当前部门，递增 `context_version`、更新全局 `active_department_id`，并立即清除原部门在所有软件下的全部上下文；同一部门下不同软件的上下文可共存。当 `switchDepartment: false` 且用户已有其他当前部门时，拒绝切换并返回 `409 DEPARTMENT_MISMATCH`。
- 验收：跨部门切换废止旧部门全部软件上下文且切回不恢复；同部门多软件上下文共存；非子集激活角色或非成员均被拒绝；`switchDepartment: false` 返回 409。

**R10 在线有效权限查询与批量校验**
业务软件凭 `X-API-Key` 和 `X-Context-Token` 调用 `GET /api/v1/contexts/effective` 查询当前有效角色与权限集合，或调用 `POST /api/v1/contexts/check` 批量校验一组权限编码。

- 有效权限严格等于当前上下文激活角色（且启用、未到期）关联权限的去重并集；未定义、未授予或已停用权限一律返回 `false`（默认拒绝）。
- 已过期、已撤销、版本落后或所属用户/软件已停用的上下文统一返回 `CONTEXT_INVALID`。
- 校验路径不加数据库行锁（走 Redis 缓存或 MySQL MVCC 只读快照读），确保高并发下零阻塞。
- 验收：权限并集准确，撤权/改角色/停用/过期/切换部门后立即反映在校验结果中，数据库时间与到期时间使用统一时钟基准。

**R11 权限诊断模拟、权限反查与即将到期报表**
- **权限诊断**（`POST /api/admin/simulate`）：不改变用户当前部门与任何数据，计算并解释用户在目标域的有效权限来源、拒绝原因，同时明确报告已过期的角色分配（不因存在过期角色而阻塞诊断）。
- **权限持有者反查**：按权限编码查询当前有效持有该权限的用户列表，并按「用户 × 部门」维度去重计数。
- **即将到期报表**：列出即将到期的角色分配，支持按可见部门与软件过滤；另提供全局跨实体搜索。
- 验收：模拟不触发部门切换且能报告已过期角色；反查计数与即将到期列表准确且遵守管理员范围。

---

### D. 授权治理：申请审批、定期复核与 Webhook

**R12 角色申请与审批流程**
业务系统可代员工提交角色申请（`POST /api/v1/access-requests`）并查询/撤销本人申请；具备 `request.approve` 的管理员在可见部门与软件范围内批准或驳回申请。

- 数据库通过 `pending_role_id` + 唯一索引 `(user_id, pending_role_id)` 保证同一用户对同一角色同时最多只有 1 条待审批记录；历史已处理记录可存在多条。
- 批准时管理员可按申请天数、自定义天数或改为永久授予角色；批准同时自动刷新用户上下文与缓存。
- 查询本人申请时必须先按当前调用软件过滤，再应用返回条数限制。
- 验收：审批通过即时生效并在到期后失效；驳回与员工撤销状态正确；并发重复申请被唯一约束拒绝；审批人可改期限或设永久。

**R13 定期权限复核与 `grantId` 精准回收**
具备 `review.manage` 的管理员可为指定软件（及可选部门）发起定期复核，逐条标记 `KEEP` / `REVOKE` 并关闭复核执行回收。

- 发起复核时对有效分配拍快照，保存 `grant_id` 以及用户名、角色名、部门名、软件名快照；即使用户或角色后续被删除，治理历史仍完整可读（外键 `ON DELETE SET NULL`）。
- 关闭复核时仅删除库中 `grant_id` 仍与快照一致的分配；若期间原授权已失效并被重新续期/重授（生成了新 `grant_id`），则跳过回收避免误伤。
- 验收：关闭复核准确回收标记项、跳过已换 `grant_id` 的续期项；删除用户或角色后申请与复核历史快照仍完整保留。

**R14 角色分配自动到期**
角色分配到达 `expires_at` 时刻起立即不再计入有效权限（不依赖后台清理延迟）；后台定时任务每 60 秒分批清理已到期分配（每批 500 条），通过主键条件删除保证多实例不重复触发，并同步刷新缓存、记录系统审计 `USER_ROLES_EXPIRED` 及发出 `user.roles.expired` Webhook 事件。

- 验收：到期时刻缓存与实时校验同步失效，定时清理触发到期通知且不重复广播。

**R15 Webhook 变更通知与可靠投递**
具备 `application.credentials` 的管理员可为软件登记、修改、启停、删除与测试 Webhook。

- **事务外盒**：所有权限变更（角色变更、权限变更、用户启停、到期、清单导入等）在同一数据库事务内写入投递外盒，保证不丢消息也不在回滚时误发。
- **签名与并发**：投递携带 `MelonHub-Event`、`MelonHub-Delivery` 和 `MelonHub-Signature: t=<秒>,v1=HMAC-SHA256(secret, "{t}.{body}")`；后台调度器按空余队列并发认领，由独立线程池并行投递，单个慢接收端既不阻塞调度器也不阻塞其他软件投递；超时（5 秒）覆盖含响应体读取在内的完整请求生命周期。
- **重试、租约与停用**：失败按 `min(60, 2^(n-1))` 分钟指数退避，最多 8 次后标记 `FAILED`；认领与写回以 `attempts` 为条件，防 5 分钟租约过期后旧线程覆盖新结果；停用 Webhook 暂不投递但保留队列，重新启用后继续投递；保留期清理只清理已终态记录，保留待发与在途记录。Webhook 列表返回标准数组契约。
- 验收：签名可验证、慢端点不互相阻塞、过期租约旧线程无法覆写、停用保留队列、保留期清理不误删待发投递。

---

### E. 平台管理员安全、功能权限与分级树

**R16 管理员登录安全、锁定、会话与凭据版本**
控制台管理员使用账号与密码登录（`POST /api/auth/login`），支持包含 `@`、`.` 的用户名（3–100 字符，不以 `.` 开头）。

- 密码要求 12–100 个字符且 UTF-8 编码不超过 **72 字节**（超出明确拒绝，绝不静默截断），采用 BCrypt（cost 12）存储；未知用户名仍执行一次 `dummyHash` 比较防计时枚举。
- 连续 5 次密码或动态口令错误锁定 15 分钟（可由上级管理员解锁）；会话最长 8 小时、空闲 30 分钟失效，支持主动退出。
- **凭据版本并发防线（`credential_version`）**：改密（保留当前会话并踢掉其他会话）、重置密码、改角色、停用账号或变更两步验证均递增 `credential_version` 并下线旧会话；登录建会话时对账号行加共享锁（`PESSIMISTIC_READ`）复核启用状态、版本、密码哈希与 TOTP 密钥均未变动，阻止与并发改密/停用/删号重建竞态产生的陈旧会话。
- 验收：锁定/解锁、空闲过期/登出、改密踢其他会话、72 字节拒绝、并发改密与删号同名重建防陈旧会话全部通过。

**R17 管理员 TOTP 两步验证**
管理员可在账号菜单绑定和停用基于 RFC 6238（HMAC-SHA1、30 秒步长、6 位数字、±1 步容差）的 TOTP 两步验证；设备丢失时由上级管理员重置。

- 绑定分「生成待确认密钥 → 首个有效口令激活」两步，激活或停用均结束旧会话。
- 数据库通过条件更新 `WHERE id = :id AND mfa_secret = :secret AND mfa_last_step < :step` 保证同一时间步的 6 位口令在并发登录或并发停用中最多只能使用一次，绝不重放。
- 验收：未带口令返 `MFA_REQUIRED`，重放同一步口令或 4 线程并发持同一口令登录仅 1 次成功。

**R18 19 项管理功能与内置/自定义管理员角色**
控制台所有 `/api/admin/**` 接口必须显式标注 `@RequiresFunction`，由 `AdminFunctionGuard` 默认拒绝未标注接口；枚举 `AdminFunction`（19 项）必须与数据库 `admin_functions` 表完全一致。

- 5 个内置角色（`SUPER_ADMIN` 全部 19 项、`ADMIN` 17 项、`APP_ADMIN`、`DEPARTMENT_ADMIN`、`AUDITOR` 5 项查看）不可修改或删除；正在被账号使用的自定义角色不可删除。
- 自定义角色的功能变更在受影响管理员的**下一次请求**立即生效；操作者不能在角色中授予自己本身不拥有的管理功能。
- **`platformAdminOnly` 硬约束**：`user.manage`（新建与编辑用户）和 `user.credentials`（管理用户登录凭据）仅对 `SUPER_ADMIN` 和 `ADMIN` 开放，自定义角色即使尝试配置也会被拒绝，前后端同步拦截；`department.manage`、`application.manage`、`application.credentials`、`role.manage` 等平台级功能仅对管理全部部门的账号开放。
- 验收：自动化反射扫描全部 `/api/admin/**` 均已标注 `@RequiresFunction`；内置角色权限与历史规则一致；自定义角色生效与权限上限校验准确。

**R19 管理员层级树与部门/软件双轴范围隔离**
管理员账号组成层级委派树（在 DDD 后端由领域模型 `AdminHierarchy` 检查不变量，在结构化后端由无状态过程集 `StructuredAdminHierarchyProcedures` 执行等价校验；任何修改在写入数据库前先锁全部账号行并在内存校验，违规则返回 `409` 绝不写库）：

- 顶级账号（`parentId == null`）管理除自己外全部账号；非顶级账号仅能管理其子树。
- 账号分两类：`MANAGER` 可再设下级；`DEPARTMENT` 部门管理员必须为叶子节点（不能有下级）、必须限定至少一个部门、且不能持有 `account.manage` 或 `role.manage`。
- 上下级链不得形成循环；下级的功能集合、部门范围和软件范围均必须是上级的子集；修改某个管理员角色的功能时同步校验全树合法性。
- 内置角色范围轴约束：`APP_ADMIN` 按「全部部门 + 指定软件」管理，`DEPARTMENT_ADMIN` 按「指定部门 + 全部软件」管理，软件范围属于账号而非角色。
- 平台至少保留 1 个启用的超级管理员；管理员不能修改自己的角色/范围/上级或停用/删除自己；被某管理员限定引用的部门不可删除。
- 验收：纯领域单测与数据库集成测试共同验证树管理权限、不越上级、防循环、叶子约束、双轴隔离、保留最后超管与受限部门防删。

**R20 管理员账号稳定数字代理主键（`V14`）**
管理员账号采用数据库自增代理主键 `id`（`BIGINT AUTO_INCREMENT`），`username` 仅作为唯一登录名；上级引用为 `parent_id`，会话及部门/软件范围关联表均以 `account_id` 关联。

- `/api/auth/login` 与 `/api/auth/me` 返回数字 `id`；`POST /api/admin/accounts` 返回 `{id, username}`；账号更新、改密、重置 MFA、解锁与删除接口均采用数字主键路径 `/api/admin/accounts/{id}`（创建与更新入参优先使用 `parentId`，保留 `parentUsername` 兼容回退）。
- 删除账号后即使以相同用户名重建，也会分配全新的数字 `id`，从主键层面杜绝历史会话或外部引用错绑。
- 验收：列表与详情返回稳定 `id` 与 `parentId`，按有效/不存在数字 ID 调用管理接口分别返回 200 / 404。

---

### F. 审计追踪、防篡改哈希链与脱敏请求日志

**R21 审计日志、范围导出与 SHA-256 哈希链防篡改**
系统对配置变更、授权变更、登录安全事件及治理操作留痕，记录操作人、动作、目标、隔离域、变更详情、来源 IP 与时间戳；支持按管理员可见范围分页查询与导出（最多 5000 条）。

- 定时任务每 10 秒按封存顺序为未封存记录分配单调递增 `seal_seq`，计算 `chain_hash = SHA-256(prev_hash | 规范化字段)`，v2 起采用「字段长度:值」规范化编码防止跨字段挪移内容伪造同一哈希。
- 晚提交的小 ID 记录自动按封存顺序接在链尾；校验接口（`GET /api/admin/audit/verify`）可精准检出 `LINK_BROKEN`、`MODIFIED` 和 `TAIL_MISSING`；保留期清理按链前缀删除并更新锚点（`anchor_seq` / `anchor_hash`），剩余链条仍可完整通过校验。
- 验收：篡改内容、跨字段挪移文本、截断链尾均被检出；晚提交入链与前缀清理后验链均通过；导出严格受可见范围约束。

**R22 HTTP 安全防线、请求追踪号与菜单请求脱敏日志**
所有进入平台的 HTTP 请求首先经过 `ApiGuard` 过滤器：

- **路径规范防绕过**：严格校验规范路径正则，拒绝含 `;`（矩阵参数）、`%`（编码绕过）、`..` 或双斜杠的非规范路径（返回 `400 INVALID_PATH`）；除 `/api/health`、`/api/auth/login` 与转交 `IdentityController` 自行鉴权的 `/auth/**` 外，所有 `/api/**` 默认拒绝未认证请求。
- **追踪号与安全响应头**：为每个请求生成或透传合法 `X-Request-Id` 写入 Logback `MDC` 和响应头，并统一附加 `X-Content-Type-Options`、`X-Frame-Options`、`Content-Security-Policy`、`Referrer-Policy`、`Cache-Control: no-store`。
- **菜单请求脱敏日志**：请求结束时对匹配路由模板的 `/api/*`（除健康检查）与 `/auth/*` 输出一行标准 Logback `INFO` 摘要 `HTTP requestId=... method=... route=... status=... durationMs=...`，其中 `route` 取自 Spring MVC 路由模板（如 `/api/admin/accounts/{id}`），严禁写入真实路径参数、查询串、Bearer Token、API Key 或密码。
- 验收：非规范路径返回 400、响应带追踪号与安全头、脱敏日志测试确认输出路由模板且不含任何凭据或用户输入。

---

### G. 统一身份认证与课程管理系统联调（`/auth/*`）

**R23 统一身份 JWT 签发、验签、会话撤销与凭据安全**
面向课程项目管理系统等接入方提供统一身份登录与会话管理：

- `POST /auth/login` 校验 `systems` 包含 `course-project-management`、凭据与用户均启用且 BCrypt 密码匹配，使用纯 JDK `HMAC-SHA256` 签发三段式 JWT（含 `sub/aud/iss/iat/exp/jti`，**不包含角色与权限**），并在 `identity_sessions` 记录令牌 SHA-256 哈希。
- 建会话时对 `users` 和 `identity_credentials` 加 `PESSIMISTIC_READ` 共享锁核对用户名、启用状态与密码哈希，防止并发改密/停用/改标识留下陈旧会话；`POST /auth/logout` 写入 `revoked_at` 立即作废令牌。
- 验签采用常量时间比较 `MessageDigest.isEqual` 防时序侧信道攻击，严格核对 `iss`、`aud` 与过期/时钟容差。
- 控制台新建用户时可勾选设置统一身份密码，已有用户可通过 `PUT /api/admin/users/{id}/identity-password` 重置密码（8–72 UTF-8 字节，仅超级/平台管理员可用）；重置密码、修改外部标识或停用用户均同步撤销全部统一身份会话。
- 验收：JWT 签发/验签/防篡改/防跨系统 `aud` 混用、登录/退出即时作废、改密校验与同步吊销全部通过。

**R24 统一身份当前用户、菜单/按钮权限、API 全局权限与组织架构查询**
登录后的统一身份调用方持 `Bearer JWT` 实时访问以下接口（每次均校验 JWT 与活跃会话）：

- `GET /auth/me`：返回 `{ subject, roles(小写), permissions, name, no }`；角色与权限实时查库（仅计入启用软件、启用角色且未到期的分配）。
- `GET /auth/permissions/{permission}`：仅返回 `{ permission, allowed }`，让浏览器网络面板可核对本次权限码及允许/拒绝结果，不携带用户资料、角色或完整权限列表；保留 `allowed` 供现有课程路由守卫判定。
- `GET /auth/api-permissions/{operationId}`：依据内置 `operationId -> 权限码` 映射表（21 项，如 `reviewCourse -> CR`、`listAuditLogs -> AV`）返回 `{ operationId, allowed }`，未登记操作返回 `404 API_OPERATION_NOT_FOUND`。
- `GET /auth/departments` 与 `GET /auth/departments/{departmentId}/users`：需 `ORG_VIEW` 权限或 `admin` 角色，支持按 `parentId` 逐层展开部门树（返回 `hasChildren`）及分页关键词搜索部门成员，供课程系统管理员添加教师选择器使用。
- `GET /auth/users/{userId}`：需 `TEC` 权限或 `admin` 角色，按统一身份标识返回只读 `{ userId, no, name }`。
- `POST /api/admin/identity/users/import`：供初始化脚本分批导入联调用户与 BCrypt 密码，同时要求 `user.manage`、`user.credentials` 与全部部门范围。
- 验收：各接口鉴权、权限码实时判定、404/403 边界及组织分页结构与 `doc/identity-openapi.yaml` 契约一致。

**R25 课程业务审计日志按授权部门隔离查询**
开放 `GET /auth/audit-logs`（配合 `V13` 为课程 `admin` 角色补齐 `AV` 权限），供课程管理系统按操作人、操作人 ID、对象与时间区间分页查询课程业务事件。

- 仅查询调用者拥有有效 `AV` 权限的部门下、`course-project-management` 软件内、动作属于 4 类课程业务事件（`score_changed`、`group_changed`、`milestone_changed`、`roster_changed`）的日志；严格过滤平台管理事件、其他部门与其他软件日志。
- 无任何部门具备 `AV` 权限时返回 `403`；页码/页大小/时间区间非法返回 `400`；未登录返回 `401`。
- 批量关联返回操作人在授权部门内的当前角色（避免 N+1 查询）；已删除操作人仍保留历史标识并标记 `deleted: true`。
- 验收：授权部门内课程业务日志分页准确，跨部门/跨软件/平台事件均不可见，撤除 `AV` 权限立即返回 403。

---

### H. 控制台前端与 Java SDK

**R26 管理控制台前端交互、权限对齐与会话竞态保护**
- 前端权限模型（`store.ts`）与后端 19 项 `AdminFunction`、`PLATFORM_WIDE` 及 `PLATFORM_ADMIN_ONLY` 规则完全对齐，操作按钮无权时置灰并明确提示原因。
- 分页与列表加载通过递增请求序号丢弃过时异步响应；响应 `401` 时比对发请求时的 Token 与当前 `sessionStorage` Token，防止旧 Token 迟到 401 误踢重新登录后的新会话。
- 验收：会话竞态测试确认旧 Token 迟到 401 不清空新会话，当前 Token 401 正确登出；前端类型检查与构建通过。

**R27 Java 业务侧 SDK（`sdk/java`）**
为 Java/Spring Boot 业务软件提供开箱即用的客户端、授权门面、Webhook 验签工具与自动装配 Starter：

- `MelonHubClient`：支持上下文签发/查询/批量校验/撤销与角色申请；超时覆盖完整响应体读取；遇到 `503`/`429` 按 `Retry-After` 退避重试，重试耗尽抛出结构化异常（保留错误码与 `requestId`）。
- `MelonHubAuthorizer`：本地缓存上下文 Token，遇到 `CONTEXT_INVALID` 自动无切部门重建一次；常规鉴权调用绝不隐式切换用户全局当前部门（`switchDepartment: false`），只有显式 `switchDepartment()` 才切换并清理其他部门缓存；服务端或网络故障保持 Fail-Closed 拒绝；收到已验签 Webhook 事件精准驱逐对应用户/域/软件缓存。
- `WebhookSignatureVerifier`：严格校验 HMAC-SHA256 签名与默认 300 秒时间戳容差防重放。
- `MelonHubAutoConfiguration` & `MelonHubPermissionInterceptor`：非 Web 应用不强依赖 Servlet/MVC，Spring MVC 应用自动装配 `@RequirePermission`（方法注解覆盖类注解与父类注解），缺头返 401、无权返 403、部门不匹配返 409、安全转义错误 JSON。
- 验收：SDK 5 个测试类全部 39 个测试方法（48 项 JUnit 执行）通过。

---

## 4. 非功能需求

**R28 权限缓存强一致性与 Redis 故障熔断恢复**
- `PermissionCache` 采用「全局 `epoch` + 随机用户/软件/密钥/域代标记 + `:busy` 标记」Lua 原子协议：写操作在数据库事务提交前换代并递增 `:busy`，提交后（`afterCommit`）再换代，完成后递减 `:busy`；读路径见 `:busy > 0` 时绝不回填缓存，消除并发回写脏数据。
- 缓存结果 TTL 不超过 60 秒且不超过结果中最早到期角色的 `refreshAt`，命中时二次检查到期时刻。
- Redis 超时（300ms）或断连触发 5 秒熔断直接回退 MySQL，记录遗漏失效并由后台每 2 秒恢复推进 `epoch`；配置 `CACHE_ENABLED=false` 时零触碰 Redis。
- 验收：重复校验命中缓存，撤权/停用/切部门/批授/密钥吊销/角色到期均即时失效；Redis 宕机或关闭时正常回退数据库。

**R29 跨实例全局限流、软件限额与降级状态看板**
- 登录接口按来源 IP 每分钟 10 次限流，业务 API 按软件每秒限额（默认 1000 次/秒，可按软件单独配置为默认、自定义值或 0 不限）；多实例经 Redis 固定窗口 Lua 脚本共享限流额度。
- `GlobalRateLimiter` 拥有独立 5 秒熔断，Redis 异常时自动降级为实例内令牌桶 `RateLimiter`，不在熔断期内逐请求重复试探 Redis。
- 系统健康检查与状态看板在数据库不可达或半途查询失败时，仍稳定返回完整响应字段（不缺失键、不返回半截脏统计）。
- 验收：Redis 跨实例共享窗口准确，故障降级不重复打 Redis，状态看板降级键结构固定。

**R30 启动前数据库目标防误连守卫与测试库隔离**
- `CloudDatabaseConfiguration` 在数据源和 Flyway 初始化前强校验 Spring Profile 与 JDBC URL：`local` 只允许回环主机、`cloud` 强制匹配配置端点并兼容 Docker Swarm 下划线服务名（如 `lab_mysql`）、禁止同时激活冲突 Profile、强制 TLS 且锁定库名 `melonhub_rbac`。
- 自动化测试（`test` Profile 与 `CloudTestDatabaseGuard`）强制显式声明目标并锁定独立测试库 `melonhub_rbac_test`，杜绝污染正式库或其他项目数据库。
- 验收：[R30_DatabaseTargetGuardUnitTest](../backend/src/test/java/com/melonhub/rbac/config/R30_DatabaseTargetGuardUnitTest.java) 与 [R30_CloudTestDatabaseGuardUnitTest](../backend/src/test/java/com/melonhub/rbac/requirements/nfr_reliability/R30_CloudTestDatabaseGuardUnitTest.java) 覆盖所有安全与非法 URL 变体。

**R31 HTTP 响应契约稳定性**
- 控制台、业务及治理核心接口的响应 JSON 结构（键名与值类型）由 [R31_ApiContractIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/nfr_reliability/R31_ApiContractIntegrationTest.java) 对照 `api-shapes.json` 持续校验，确保底层重构（如 DAO 迁移至 JPA、`CloneFactory` 契约改造、管理员数字 ID 升级）不破坏既有前后端与 SDK 契约。
- 验收：`R31_ApiContractIntegrationTest` 与 `doc/API.md`、`doc/identity-openapi.yaml` 保持一致。

**R32 容器化部署、无损迁移与可运维性**
- 表结构完全由 Flyway `V1–V17` 管理，Hibernate 配置 `ddl-auto: validate` 与 `open-in-view: false`；`V8` 兼容旧库无损恢复「部门 × 软件」模型，`V14` 无损回填 `admin_accounts` 数字主键，`V15`、`V16` 为角色权限和用户角色补齐隔离域复合外键，无需删库或 `flyway repair`。
- Docker Swarm 部署（`deploy/stack.yml`）接入共享覆盖网络 `javaee-proxy`。后端使用 Actuator 健康检查。前端使用 Nginx 将 `/api/`、`/auth/` 同源转发至后端，健康探测验证 `/api/health` 返回 `status: UP`。前后端支持 `start-first` 更新与失败回滚。
- 后台定时维护分批清理过期上下文、管理员会话、到期分配与超期 Webhook/审计记录（保留锚点）。
- 验收：权限系统与课程系统的前后端共四个服务均部署在 Docker Swarm，使用 `docker service ls` 可见且副本均为 `1/1`；`/api/health` 与 `/actuator/health` 正常；联调与菜单日志流程按 `doc/双系统操作手册.md` 可复现。

**R34 RBAC1 角色层级（`role_inheritances`）**
- 同一部门 × 软件隔离域内，角色可以继承一个或多个父角色的全部有效权限。用户分配子角色时，权限计算沿父角色链递归展开并去重。
- 管理接口 `PUT /api/admin/roles/{id}/parents` 与业务接口 `/api/v1/departments/{dept}/roles/{id}/parents` 整体替换父角色集合。父角色必须属于同一隔离域，不能包含自身，不能形成循环。
- 角色继承变更会替换域缓存代、使下一次上下文校验按新层级计算，并发出 `role.inheritance.changed` Webhook 事件。V17 以复合外键保证数据库层面的隔离域一致性。

---

**R33 结构化与 DDD 双策略后端严格分离（`melonhub.backend-strategy=structured|ddd`）**
- 后端以 `service.port`（10 个共享用例端口）解耦入口层（`controller`、`security`、`DemoData`）与服务实现，通过配置项 `melonhub.backend-strategy` 排他装配一整套结构化后端（`service.structured`，默认）或一整套 DDD 分层后端（`service.ddd.{domain,application,infrastructure}`）。
- `service.structured` 严禁引用任何 `service.ddd` 领域对象；`service.ddd.domain` 保持不依赖框架的 Java 实现（严禁 Spring/JPA/Jackson/DAO 导入）；`service.ddd.application` 仅经领域仓储/网关端口访问外围基础设施；两套实现应遵循同一 API 契约、错误码、事务边界、锁序、缓存失效与审计语义。已执行场景的结果一致不等于所有输入与并发时序下的等价性证明。
- 验收：[ArchitectureBoundaryUnitTest](../backend/src/test/java/com/melonhub/rbac/requirements/nfr_reliability/ArchitectureBoundaryUnitTest.java)、[BackendStrategyWiringUnitTest](../backend/src/test/java/com/melonhub/rbac/requirements/nfr_reliability/BackendStrategyWiringUnitTest.java) 与 [DddDomainAndDualStrategyParityUnitTest](../backend/src/test/java/com/melonhub/rbac/requirements/nfr_reliability/DddDomainAndDualStrategyParityUnitTest.java) 全量通过。

---

## 5. 需求与测试代码对照矩阵

本表列出已有自动化场景及代码位置，**不表示每条需求已完整覆盖**。按测试方法存在、实际执行结果与现场验证三个层次分别判断。后端测试既有普通单测，也有参数化双策略用例，因此测试方法数、测试类数与执行项数不同。

2026-11-24 PR #8 复审的执行证据如下；详细环境、日志位置和线上探测见 [验证记录](验证记录.md)。本次材料补齐不把旧浏览器记录当作新一轮界面验证。

| 套件 | 本次基线结果 | 范围与排除项 |
| --- | --- | --- |
| 后端 `*UnitTest` | 65 项通过 | 包括架构边界、双策略装配及选定领域/过程规则 |
| structured MySQL 集成 | 109 项通过 | 专用 `melonhub_rbac_test`，缓存关闭 |
| ddd MySQL 集成 | 109 项通过 | 同一测试集合，缓存关闭 |
| Java SDK | 48 项通过 | SDK 独立 Maven 工程 |
| 前端自动化 | 1 项会话竞态测试通过，类型检查与构建通过 | 不构成全页面或端到端功能覆盖 |
| Redis 集成 | 本次未执行 | 排除 `R14_R28_PermissionCacheIntegrationTest` 和 `R29_GlobalRateLimiterAndHealthIntegrationTest`；R14/R28/R29 的相关能力不能据本次结果标为全绿 |
| Swarm 与接口探测 | 四个服务各 1/1；登录、选定读取接口及请求日志通过 | 不等同逐项浏览器操作演示；课程实际权限链路见 [专项核对](课程权限接入核对.md) |

只运行不依赖真实数据库的单测：

```powershell
mvn -f backend/pom.xml test "-Dtest=*UnitTest"
```

集成测试需先按 [本地运行说明](本地Docker运行.md) 配置专用测试库，不得直接指向正式库。类名或方法列只是覆盖索引，不是需求穷尽性证明。

| 需求编号 | 需求简述 | 对应自动化测试类（可直接点击定位） | 精准测试方法（核心验收项） | 覆盖状态 |
| --- | --- | --- | --- | --- |
| **R1** | 部门管理与依赖保护 | [R01_R04_OrgUserAndAppIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/a_org_app/R01_R04_OrgUserAndAppIntegrationTest.java)<br>[R19_AdminTreeIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/e_admin_security/R19_AdminTreeIntegrationTest.java) | `deletingDepartmentOrApplicationInUseNamesWhatRemains`<br>`aDepartmentThatLimitsAnAdministratorCannotBeDeleted` | 已列场景通过 |
| **R2** | 用户资料、多部门归属、状态与导出 | [R01_R04_OrgUserAndAppIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/a_org_app/R01_R04_OrgUserAndAppIntegrationTest.java)<br>[R02_UserAndAuditExportIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/a_org_app/R02_UserAndAuditExportIntegrationTest.java) | `disabledUserCannotReuseOldContextAfterReactivation`、`editingDisplayFieldsKeepsContextsButChangingExternalIdEndsThem`、`removingMembershipEndsContextsInThatDepartment`、`forcedSignOutEndsEveryContextOfTheUser`、`usersArePagedAndSearchable`<br>`exportsIncludeAllFilteredUsersAndOnlyVisibleAuditRows` | 已列场景通过 |
| **R2a** | 用户批量导入（部分成功逐行报错） | [R02a_UserBulkImportIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/a_org_app/R02a_UserBulkImportIntegrationTest.java)<br>[R02a_UserBulkImportValidationIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/a_org_app/R02a_UserBulkImportValidationIntegrationTest.java) | `bulkImportCreatesUpdatesAndReportsRowErrors`<br>`bulkImportReportsInvalidRowsInsteadOfRejectingTheRequest` | 已列场景通过 |
| **R3** | 接入软件生命周期与停用失效 | [R01_R04_OrgUserAndAppIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/a_org_app/R01_R04_OrgUserAndAppIntegrationTest.java)<br>[R14_R28_PermissionCacheIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/nfr_reliability/R14_R28_PermissionCacheIntegrationTest.java) | `deletingDepartmentOrApplicationInUseNamesWhatRemains`、`disabledApplicationLosesKeysAndContexts`<br>`revokedOrDisabledApiKeysStopWorkingImmediately` | MySQL 场景通过；Redis 场景未复验 |
| **R4** | 多 API Key 管理、有效期与轮换 | [R01_R04_OrgUserAndAppIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/a_org_app/R01_R04_OrgUserAndAppIntegrationTest.java)<br>[R14_R28_PermissionCacheIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/nfr_reliability/R14_R28_PermissionCacheIntegrationTest.java) | `apiKeyIsBoundToApplicationAndCannotAssignForeignRoles`、`adminAndBusinessEndpointsRequireDistinctCredentials`、`keyRotationRejectsOldKeyImmediately`、`applicationsCanHoldSeveralKeysWithExpiryAndRevocation`<br>`revokedOrDisabledApiKeysStopWorkingImmediately` | MySQL 场景通过；Redis 场景未复验 |
| **R5** | 隔离域（部门 × 软件）物理与逻辑隔离 | [R05_R08_DomainRbacIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/b_rbac_domain/R05_R08_DomainRbacIntegrationTest.java) | `crossDomainRolePermissionAssignmentsAreRejectedAtomically`、`databaseForeignKeysAlsoRejectCrossDomainRelationships`、`businessCrudCannotEditOrDeleteRolesInAnotherDomain` | 已列场景通过 |
| **R6** | 角色/权限动态配置与角色成员防死锁 | [R05_R08_DomainRbacIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/b_rbac_domain/R05_R08_DomainRbacIntegrationTest.java)<br>[R06_RoleMembersConcurrencyIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/b_rbac_domain/R06_RoleMembersConcurrencyIntegrationTest.java)<br>[R06_R08_GrantExpiryAndManifestIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/b_rbac_domain/R06_R08_GrantExpiryAndManifestIntegrationTest.java) | `permissionRevocationIsVisibleInExistingContext`、`roleMembersCanBeManagedFromTheRoleSide`<br>`aMemberWhoJoinsDuringAReplacementDoesNotDeadlockIt`<br>`roleMemberAdditionRecordsWhoGrantedWhichMember` | 已列场景通过 |
| **R7** | 用户角色分配、逐角色有效期与批量追加 | [R05_R08_DomainRbacIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/b_rbac_domain/R05_R08_DomainRbacIntegrationTest.java)<br>[R06_R08_GrantExpiryAndManifestIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/b_rbac_domain/R06_R08_GrantExpiryAndManifestIntegrationTest.java)<br>[R07_BatchGrantScopeIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/b_rbac_domain/R07_BatchGrantScopeIntegrationTest.java) | `removingUserRoleInvalidatesContext`、`reassigningRolesInOneApplicationKeepsContextsOfOtherApplications`<br>`reassigningKeepsUntouchedExpiriesAndAcceptsPerRoleOnes`、`batchAddsSeveralRolesToSeveralMembersAndKeepsExistingGrants`、`batchCannotAssignAcrossDepartmentsOrRolesOutsideTheDomain`、`staleTimedFormCannotRecreateAPurgedGrantPermanently`<br>`batchGrantHonorsApplicationWriteScope` | 已列场景通过 |
| **R8** | 跨部门复制与权限清单导入导出 | [R05_R08_DomainRbacIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/b_rbac_domain/R05_R08_DomainRbacIntegrationTest.java)<br>[R06_R08_GrantExpiryAndManifestIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/b_rbac_domain/R06_R08_GrantExpiryAndManifestIntegrationTest.java) | `domainCopySkipsExistingCodesAndCopiesGrants`<br>`manifestExportAndImportRoundTrip`、`manifestImportAnnouncesRolesItDisablesOrRegrants` | 已列场景通过 |
| **R9** | 上下文签发与全局当前部门切换 | [R09_R11_ContextAndCheckIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/c_online_context/R09_R11_ContextAndCheckIntegrationTest.java)<br>[R09_R11_SwitchGuardAndReportIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/c_online_context/R09_R11_SwitchGuardAndReportIntegrationTest.java) | `departmentSwitchInvalidatesOldContextsAcrossApplicationsAndSwitchBack`、`sameDepartmentInDifferentApplicationsKeepsBothContextsValid`、`activeRolesMustBeAssignedSubset`、`nonmemberCannotSwitchDepartments`<br>`contextCreationCanRefuseToSwitchTheCurrentDepartment` | 已列场景通过 |
| **R10** | 在线有效权限查询与批量校验 | [R09_R11_ContextAndCheckIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/c_online_context/R09_R11_ContextAndCheckIntegrationTest.java) | `timestampsAndDatabaseExpiryUseTheSameClock`、`effectivePermissionsAreUnionOfAssignedRolesOnly`、`expiredAndRevokedContextsAreRejected` | 已列场景通过 |
| **R11** | 权限诊断模拟、权限反查与到期报表 | [R09_R11_ContextAndCheckIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/c_online_context/R09_R11_ContextAndCheckIntegrationTest.java)<br>[R09_R11_SwitchGuardAndReportIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/c_online_context/R09_R11_SwitchGuardAndReportIntegrationTest.java) | `simulationExplainsAccessWithoutSwitchingDepartments`<br>`simulationReportsLapsedRolesWithoutBlocking`、`reverseLookupSearchAndStatus`、`permissionHoldersAreCountedPerUserAndDepartment`、`expiringAssignmentsAppearInReport` | 已列场景通过 |
| **R12** | 角色申请与审批流程 | [R12_AccessRequestApprovalIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/d_governance/R12_AccessRequestApprovalIntegrationTest.java) | `approvedRequestGrantsRoleUntilItsExpiry`、`rejectionAndEmployeeCancellation`、`ownRequestsAreFilteredByApplicationBeforeTheyAreLimited`、`theDatabaseKeepsOnePendingRequestPerUserAndRole`、`approverCanGrantPermanentlyOrForAnotherDuration`、`governanceEndpointsAreExposedAndGuarded` | 已列场景通过 |
| **R13** | 定期权限复核与 `grantId` 精准回收 | [R13_AccessReviewIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/d_governance/R13_AccessReviewIntegrationTest.java) | `closingReviewAppliesRevokeDecisions`、`closingReviewOnlyRevokesTheGrantItSnapshotted`、`governanceHistorySurvivesDeletingTheUserOrRole` | 已列场景通过 |
| **R14** | 角色分配自动到期与到期通知 | [R14_R28_PermissionCacheIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/nfr_reliability/R14_R28_PermissionCacheIntegrationTest.java) | `lapsedAssignmentEndsCachedDecisionsOnTimeAndIsAnnounced` | 所列 Redis 集成场景本次未执行 |
| **R15** | Webhook 事务外盒、签名、并发与租约 | [R15_WebhookDeliveryIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/d_governance/R15_WebhookDeliveryIntegrationTest.java)<br>[R15_WebhookConcurrencyIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/d_governance/R15_WebhookConcurrencyIntegrationTest.java)<br>[R15_WebhookLeaseDaoIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/d_governance/R15_WebhookLeaseDaoIntegrationTest.java)<br>[R15_WebhookTimeoutUnitTest](../backend/src/test/java/com/melonhub/rbac/service/R15_WebhookTimeoutUnitTest.java)（纯单测） | `webhookDeliveryIsSignedAndFailuresAreRescheduled`、`disabledWebhookKeepsItsQueueUntilEnabledAgain`、`staleWebhookReadsCannotClaimOrFinishADelivery`、`webhookListReturnsArraysAndPermissionMutationsAreAnnounced`<br>`slowWebhookReceiversNeitherHoldTheSchedulerNorEachOther`<br>`retentionPreservesPendingAndInFlightDeliveries`、`expiredFinalAttemptFailsInsteadOfStartingAnotherAttempt`、`expiredEarlierAttemptRetriesWhileLiveLeaseIsPreserved`<br>`responseHeadersDoNotEndTheDeliveryDeadline` | 已列场景通过 |
| **R16** | 管理员登录、锁定、会话与凭据版本 | [R16_AdminAuthAndSessionIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/e_admin_security/R16_AdminAuthAndSessionIntegrationTest.java) | `repeatedFailuresLockAnAccountUntilUnlocked`、`disablingAnAccountEndsItsSessionsAndBlocksLogin`、`idleSessionsExpireAndLogoutEndsTheSession`、`changingOwnPasswordSignsOutOtherSessions`、`credentialChangesEndSessionsIncludingOneStillBeingCreated`、`aLoginCheckedAgainstADeletedAccountCannotOpenASessionForItsNamesake`、`accountNamesWithAtSignsAndDotsStillSupportLogin`、`passwordsBeyondSeventyTwoBytesAreRefusedInsteadOfTruncated` | 已列场景通过 |
| **R17** | 管理员 TOTP 两步验证与防并发重放 | [R17_AdminTotpMfaIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/e_admin_security/R17_AdminTotpMfaIntegrationTest.java)<br>[R17_AdminTotpConcurrencyIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/e_admin_security/R17_AdminTotpConcurrencyIntegrationTest.java) | `mfaRequiresCurrentCodeAndNeverReplaysOne`<br>`concurrentLoginsCannotShareOneTotpCode` | 已列场景通过 |
| **R18** | 19 项管理功能与内置/自定义角色 | [R18_AdminFunctionGuardIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/e_admin_security/R18_AdminFunctionGuardIntegrationTest.java)<br>[R18_AdminRoleIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/e_admin_security/R18_AdminRoleIntegrationTest.java)<br>[R18_R19_AdminBuiltInScopeIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/e_admin_security/R18_R19_AdminBuiltInScopeIntegrationTest.java)<br>[R19_AdminTreeIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/e_admin_security/R19_AdminTreeIntegrationTest.java) | `functionCatalogMatchesTheDatabase`、`everyConsoleEndpointDeclaresAFunction`、`builtinRolesKeepTheAccessOfTheFormerFixedRoles`<br>`customRoleGrantsExactlyItsFunctionsAndChangesApplyOnTheNextRequest`、`builtinRolesCannotBeChangedAndRolesInUseCannotBeDeleted`、`operatorsCannotGrantFunctionsTheyDoNotHold`<br>`auditorCanReadButNotWrite`、`accountManagementIsReservedForSuperAdministrators`<br>`onlySuperAndPlatformAdministratorsCanChangeUserProfilesAndCredentials` | 已列场景通过 |
| **R19** | 管理员层级树与部门/软件双轴隔离 | [R19_AdminTreeUnitTest](../backend/src/test/java/com/melonhub/rbac/requirements/e_admin_security/R19_AdminTreeUnitTest.java)（纯单测）<br>[R19_AdminTreeIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/e_admin_security/R19_AdminTreeIntegrationTest.java)<br>[R18_R19_AdminBuiltInScopeIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/e_admin_security/R18_R19_AdminBuiltInScopeIntegrationTest.java)<br>[R18_AdminRoleIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/e_admin_security/R18_AdminRoleIntegrationTest.java) | `rootsManageEveryoneAndManagersTheirSubtree`、`aChildNeverExceedsItsParent`、`departmentAdministratorsAreLimitedLeavesWithoutAccountManagement`、`parentChainsMustEndAtARoot`<br>`managersCreateAdministratorsOnlyWithinTheirOwnScope`、`departmentAdministratorsSeeAndChangeOnlyTheirDepartments`、`roleChangesMustKeepEveryAccountWithinItsParent`、`departmentAndSoftwareAdminsUseDifferentScopeAxes`、`aDepartmentThatLimitsAnAdministratorCannotBeDeleted`<br>`applicationAdministratorIsConfinedToAssignedApplications`、`theLastEnabledSuperAdministratorCannotBeRemoved`<br>`applicationScopeBelongsToTheAccountNotTheRole` | 已列场景通过 |
| **R20** | 管理员账号稳定数字代理主键（`V14`） | [R20_AdminAccountIdIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/e_admin_security/R20_AdminAccountIdIntegrationTest.java) | `accountListAndNumericEndpointExposeStableId` | 已列场景通过 |
| **R21** | 审计日志、导出与 SHA-256 哈希链 | [R21_AuditDomainDetailIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/f_audit_guard/R21_AuditDomainDetailIntegrationTest.java)<br>[R21_AuditHashChainIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/f_audit_guard/R21_AuditHashChainIntegrationTest.java)<br>[R02_UserAndAuditExportIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/a_org_app/R02_UserAndAuditExportIntegrationTest.java) | `auditRecordsDomainIdsAndChangeDetails`<br>`auditChainVerifiesAndDetectsTampering`、`lateCommittedRowsJoinTheChainAndRemovedTailIsDetected`、`retentionCleanupCutsAPrefixAndLeavesAVerifiableChain`、`movingTextBetweenAuditFieldsIsDetected`<br>`exportsIncludeAllFilteredUsersAndOnlyVisibleAuditRows` | 已列场景通过 |
| **R22** | HTTP 安全防线、追踪号与脱敏请求日志 | [R22_ApiGuardSecurityIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/f_audit_guard/R22_ApiGuardSecurityIntegrationTest.java)<br>[R22_ApiGuardRequestLogUnitTest](../backend/src/test/java/com/melonhub/rbac/requirements/f_audit_guard/R22_ApiGuardRequestLogUnitTest.java)（纯单测） | `nonCanonicalPathsCannotBypassAuthentication`、`everyApiPathExceptHealthAndLoginNeedsCredentials`、`responsesCarryRequestIdAndSecurityHeaders`<br>`recordsMenuRequestWithoutCredentialsOrUserInput` | 已列场景通过 |
| **R23** | 统一身份 JWT、登录/退出与改密吊销 | [R23_IdentityJwtTokenUnitTest](../backend/src/test/java/com/melonhub/rbac/requirements/g_identity/R23_IdentityJwtTokenUnitTest.java)（纯单测）<br>[R23_R25_IdentityServiceUnitTest](../backend/src/test/java/com/melonhub/rbac/requirements/g_identity/R23_R25_IdentityServiceUnitTest.java)（纯单测） | `RIdentityTokenCanBeIssuedAndVerified`、`RIdentityTokenRejectsTamperingAndWrongAudience`<br>`loginIssuesJwtAndCreatesSessionWithCredentialCheck`、`logoutRevokesActiveTokenAndSetPasswordRevokesAllSessions` | 所列单测通过；线上仅抽查登录/权限 |
| **R24** | 统一身份当前用户、权限与组织查询 | [R23_R25_IdentityServiceUnitTest](../backend/src/test/java/com/melonhub/rbac/requirements/g_identity/R23_R25_IdentityServiceUnitTest.java)（纯单测）<br>[R19_AdminTreeIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/e_admin_security/R19_AdminTreeIntegrationTest.java) | `currentUserAndPermissionEndpointsQueryLiveAuthorizations`、`organizationAndUserLookupEnforcePermissionsAndReturnNotFound`<br>`onlySuperAndPlatformAdministratorsCanChangeUserProfilesAndCredentials` | 所列单测通过；线上仅抽查登录/权限 |
| **R25** | 课程业务审计日志按授权部门隔离查询 | [R25_IdentityCourseAuditIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/g_identity/R25_IdentityCourseAuditIntegrationTest.java)<br>[R23_R25_IdentityServiceUnitTest](../backend/src/test/java/com/melonhub/rbac/requirements/g_identity/R23_R25_IdentityServiceUnitTest.java)（纯单测） | `queriesOnlyCourseBusinessEventsInAuthorizedDepartments`<br>`auditLogsForbidUnauthorizedUserAndMarkDeletedActors` | 已列场景通过 |
| **R26** | 管理控制台前端与会话竞态保护 | [api.test.mjs](../frontend/tests/api.test.mjs) | `a late 401 from the old token leaves the new session signed in`（另含 TypeScript 类型检查与 Vite 构建） | 仅会话竞态 1 项及类型/构建通过 |
| **R27** | Java 业务侧 SDK | [MelonHubClientTest](../sdk/java/src/test/java/com/melonhub/client/MelonHubClientTest.java) (10)<br>[MelonHubAuthorizerTest](../sdk/java/src/test/java/com/melonhub/client/MelonHubAuthorizerTest.java) (10)<br>[MelonHubPermissionInterceptorTest](../sdk/java/src/test/java/com/melonhub/client/MelonHubPermissionInterceptorTest.java) (11)<br>[WebhookSignatureVerifierTest](../sdk/java/src/test/java/com/melonhub/client/WebhookSignatureVerifierTest.java) (5)<br>[MelonHubAutoConfigurationTest](../sdk/java/src/test/java/com/melonhub/client/MelonHubAutoConfigurationTest.java) (3) | 覆盖客户端超时与重试、本地上下文缓存与 Fail-Closed、不隐式切部门、Webhook 验签与缓存驱逐、`@RequirePermission` 拦截器与自动装配全 39 项用例（48 项执行） | 已列场景通过 |
| **R28** | Redis 权限缓存强一致与故障熔断 | [R14_R28_PermissionCacheIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/nfr_reliability/R14_R28_PermissionCacheIntegrationTest.java)<br>[R28_PermissionCacheOutageUnitTest](../backend/src/test/java/com/melonhub/rbac/requirements/nfr_reliability/R28_PermissionCacheOutageUnitTest.java)（纯单测） | `repeatedChecksAreServedFromRedisAndRevocationIsImmediate`、`departmentSwitchAndUserDisableApplyDespiteCachedDecisions`、`lapsedAssignmentEndsCachedDecisionsOnTimeAndIsAnnounced`、`batchGrantInvalidatesCachedContextAndNewContextIncludesTheRole`、`revokedOrDisabledApiKeysStopWorkingImmediately`<br>`writesProceedAndLookupsFallBackToTheDatabaseWhileRedisIsDown`、`disabledCacheNeverTouchesRedis` | 降级单测通过；Redis 集成未执行 |
| **R29** | 跨实例全局限流与降级状态看板 | [R29_GlobalRateLimiterAndHealthIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/nfr_reliability/R29_GlobalRateLimiterAndHealthIntegrationTest.java)<br>[R29_GlobalRateLimiterUnitTest](../backend/src/test/java/com/melonhub/rbac/requirements/nfr_reliability/R29_GlobalRateLimiterUnitTest.java)（纯单测）<br>[R29_ConsoleStatusDegradeUnitTest](../backend/src/test/java/com/melonhub/rbac/requirements/nfr_reliability/R29_ConsoleStatusDegradeUnitTest.java)（纯单测） | `globalRateLimiterSharesOneRedisWindow`、`healthReportsDatabaseAndCache`<br>`redisFailureFallsBackToTheLocalBucketWithoutRetryingRedisOnEveryRequest`<br>`anUnreachableDatabaseStillAnswersWithEveryKey`、`aDatabaseFailingHalfwayDropsWhatItAlreadyReturned` | 降级单测通过；Redis 集成未执行 |
| **R30** | 数据库目标防误连守卫与测试库隔离 | [R30_DatabaseTargetGuardUnitTest](../backend/src/test/java/com/melonhub/rbac/config/R30_DatabaseTargetGuardUnitTest.java)（纯单测）<br>[R30_CloudTestDatabaseGuardUnitTest](../backend/src/test/java/com/melonhub/rbac/requirements/nfr_reliability/R30_CloudTestDatabaseGuardUnitTest.java)（纯单测） | 覆盖 `local`/`cloud`/`test` Profile、Swarm 下划线服务名（`lab_mysql`）、TLS 强制与非法 URL 拦截（合计 21 个测试执行） | 已列场景通过 |
| **R31** | HTTP 响应契约稳定性 | [R31_ApiContractIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/nfr_reliability/R31_ApiContractIntegrationTest.java) | `responseShapesMatchTheRecordedContract` | 记录的响应样本通过，非所有接口穷举 |
| **R32** | 容器化部署与无损迁移（`V1–V17`） | 全部 `@SpringBootTest` + Flyway + Swarm 复验 | Flyway `V1–V17` + Hibernate `ddl-auto: validate` 启动强校验 + 云端四服务 Swarm 联调验证（见 `doc/双系统操作手册.md` 与 `doc/验证记录.md`） | 已验证启动、迁移状态与选定接口；非全流程演示 |
| **R34** | RBAC1 角色层级与继承权限 | [R05_R08_DomainRbacIntegrationTest](../backend/src/test/java/com/melonhub/rbac/requirements/b_rbac_domain/R05_R08_DomainRbacIntegrationTest.java)、V17 Flyway 与控制器/DAO实现 | 同域父角色配置、循环和跨域拒绝、上下文权限沿父角色链展开；structured 与 ddd 均使用同一角色继承持久化契约 | structured 与 ddd 各 1 项专项继承场景通过，另有 V17 启动迁移验证 |
| **R33** | 结构化与 DDD 双策略后端分离与等价性 | [ArchitectureBoundaryUnitTest](../backend/src/test/java/com/melonhub/rbac/requirements/nfr_reliability/ArchitectureBoundaryUnitTest.java)（纯单测）<br>[BackendStrategyWiringUnitTest](../backend/src/test/java/com/melonhub/rbac/requirements/nfr_reliability/BackendStrategyWiringUnitTest.java)（纯单测）<br>[DddDomainAndDualStrategyParityUnitTest](../backend/src/test/java/com/melonhub/rbac/requirements/nfr_reliability/DddDomainAndDualStrategyParityUnitTest.java)（纯单测） | 覆盖 4 条源码依赖边界守护、Spring `structured`/`ddd` 全量 10 端口排他装配、选定领域规则与双策略行为对照（合计 18 项单测执行；并配合 R15/R19/R23–R25/R29 参数化双策略单测） | 边界/装配/选定决策通过；非全行为等价证明 |

---

## 6. 与旧版需求草案及历史演进的冲突裁决记录

> 本节汇总早期《统一权限系统软件需求说明书（1.0 草案，2026-10-24）》及开发过程对话中与现行代码（`8bd2fcd`）存在差异的条目，**均以现行代码实现为最终权威口径**：

| 冲突项 | 旧版 SRS 草案 / 历史对话表述 | 现行代码裁决（权威口径） | 裁决理由 |
| --- | --- | --- | --- |
| **1. 用户部门归属模型** | 旧 SRS `BR01/UC04`：普通人员恰有一个主部门，仅领导可配置兼管部门 | **支持任意用户通过 `memberships` 属于零个或多个部门**，在线授权统一通过「用户全局当前部门（`active_department_id`）」与切换部门失效旧上下文控制隔离（`R2`、`R9`） | 去掉与业务耦合的“领导/主部门”职级假设，严格保持权限平台不感知业务人事规则的边界 |
| **2. 隔离域演进（V6/V7 → V8）** | 中期对话（对话 `03`、`04`、`12`）曾短暂将角色与权限改为按软件共享（`V6/V7`） | **`V8` 已无损恢复为「部门 × 软件」二维隔离域**，并由复合外键兜底（`R5`） | 回归最初需求定位与多部门同软件独立授权要求，同时保留 `V6/V7` 通过云端 Flyway 历史校验 |
| **3. 调岗授权回收顺序** | 旧 SRS `BR07/UC04/UC10`：必须先手工执行全部回收，才允许修改归属 | **将用户移出某部门时原子清除该部门下的角色分配并使旧上下文失效**；跨部门切换立即作废原部门上下文；停用/改标识/强制下线作废全部上下文（`R2`、`R9`） | 避免遗漏回收产生悬空权限，同时保留新部门显式授权原则（绝不自动复制角色） |
| **4. 平台自身权限体系** | 旧 SRS `BR10`：权限平台自身作为一个接入应用，不固定管理员等级 | **平台拥有独立于业务 RBAC1 的管理员模型**：19 项 `AdminFunction` + 5 个不可变内置角色 + 自定义角色 + 管理员分级委派树（结构化 `StructuredAdminHierarchyProcedures` / DDD `AdminHierarchy`）+ `V14` 自增数字主键 `id`（`R18`–`R20`） | 隔离平台治理与业务数据风险，支持按部门/软件双轴委派且保留最后一位启用超级管理员兜底 |
| **5. 内部申请审批与复核** | 旧 SRS `1.2`：不包含审批流程，审批全部在外部 OA 完成 | **既支持外部系统通过 `/api/v1/access-requests` 对接，也内置角色申请审批、定期复核（`grantId`）与到期治理**（`R12`–`R14`） | 满足无 OA 场景下的完整授权治理闭环，业务软件无需自建权限申请与复核模块 |
| **6. 会话空闲超时配置** | 旧 SRS `FR15/UC15`：管理界面可按应用动态配置会话空闲超时 | **管理员会话时长（默认 8 小时）与空闲超时（默认 30 分钟）由服务端配置项 `melonhub.admin-security.*` 统一配置**，不提供按应用动态修改界面；业务上下文固定 1 小时（`R16`、`R9`） | 简化安全基线并避免运行期配置变更引起存量会话语义混乱 |
| **7. 统一身份联调能力** | 旧 SRS `TBD01`：未确定统一身份登录协议 | **已按课程管理系统 OpenAPI 实现 `/auth/*` 9 个统一身份接口**（JWT + `identity_sessions` 即时吊销 + 部门树 + 课程审计日志查询，见 `R23`–`R25`） | 直接支撑第二轮与课程管理平台 `CourseProjectMgt` 的 Docker Swarm 双系统真实联调 |
| **8. 日志查询留痕与审计防篡改** | 旧 SRS `BR11/UC14`：要求日志查询本身写入业务审计日志 | **管理与授权变更写入 `audit_events` 并封存 SHA-256 哈希链（`R21`）；所有含日志查询在内的控制台/身份 HTTP 请求均由 `ApiGuard.logCompletion()` 写入脱敏 Logback 请求日志（`R22`）** | 避免只读查询高频膨胀防篡改哈希链，同时通过 `X-Request-Id` 保证完整可追溯 |

---

## 7. 联调边界与待演进事项（对标 `CourseProjectMgt`）

为便于与教师示例平台 [CourseProjectMgt](https://gitee.com/mingqcn/course-project-mgt) 持续演进，记录当前 4 项已知接口与加固边界（需按实际调用和验证范围判断，不等同本轮必补缺陷）：

1. **外部审计日志写入接口（`POST /auth/audit-logs`）**：MelonHub 已实现 `GET /auth/audit-logs`（`R25`）按授权部门查询课程业务事件；`CourseProjectMgt` 的 `UnifiedAuditClient` 另定义了事务提交后写入接口 `POST /auth/audit-logs`（带 `requestId` 幂等键及 `before/after/termId/courseId/classId` 扩展字段），后续可在 MelonHub 补齐写入入口与扩展列以打通闭环。
2. **课程 API `operationId` 映射边界**：`resetCourseToApplication`、`setClassTeachers`、`listUsers` 未加入当前映射；2026-11-24 已核对老师系统身份客户端和实际运行镜像中的 499 个应用类，未发现这些操作名或 `/auth/api-permissions` 调用。当前课程后端通过 `/auth/me` 获取权限集合。此处不是当前接入缺陷，本次不补映射；换版后重新核对。见 [课程权限接入核对](课程权限接入核对.md)。
3. **统一身份登录防爆破与过期会话清理**：管理后台 `/api/auth/login` 已具备 IP 限流、5 次失败锁定与 `dummyHash` 防枚举；后续可将 `/auth/login` 同样接入 `GlobalRateLimiter` 与失败锁定，并在 `MaintenanceUseCase`（`StructuredMaintenanceService` / `DddMaintenanceService`）补充对过期/已撤销 `identity_sessions` 的分批清理。
4. **模糊检索通配符转义统一**：`IdentityDao.courseAuditEvents()` 已使用 `Jpql.like()` + `escape '!'`，后续可将 `IdentityDao` 中更早的部门与成员搜索查询同步改为 `Jpql.like()`。
