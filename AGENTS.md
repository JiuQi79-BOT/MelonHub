# MelonHub 开发规范与任务交接

最后更新：2026-10-18（Asia/Shanghai）。工作目录：`D:\PersonalStudy\MelonHub`。本文件描述当前状态，已清理服务器关机、本地库和未完成配置等过时交接信息。

## 1. 用户要求与边界

- 用户要求验证整个数据链与服务启动；说明文档统一中文，在 `doc/` 提供详细使用说明书并关联 README；提供一键启动和一键停止本项目服务的脚本。
- 用户希望能在 IntelliJ IDEA 中维护、运行和调试项目，方便管理生命周期。已有共享运行配置，本轮实际启动验证使用脚本；不能把 IDEA 图形界面启动描述成已成功验证。
- 数据库必须使用现有华为云 Swarm 中的 MySQL；不连接本机 MySQL，不回退本地库。
- 只使用本项目 `melonhub_rbac` 和独立 `melonhub_rbac_test`，不得修改其他应用数据库和数据，不重建 Swarm、不删除持久化目录、不执行实验初始化 SQL。
- MySQL 按用户要求使用 root；SSH 用户也已验证为 root，但两者是不同的账号体系。
- 实际服务器地址、SSH/MySQL 密码和管理员密码仅保存在根目录忽略文件 `.env`。不要打印原文，不写进示例、文档、日志或命令行参数。
- 用户认为审计早 8 小时如不影响使用可暂不处理；回归确认原配置也会延长管理员会话期限，因此仅修正了本项目 JDBC 连接时区，没有修改 Linux 或 MySQL 全局时区。
- 中文沟通，直接推进；只读诊断和常规修复不反复询问。修改前先阅读当前文件和相关代码，尊重用户已有编辑。

## 2. 工程与目录

| 项目 | 当前情况 |
| --- | --- |
| 后端 | Spring Boot 3.5.7、Spring JDBC、Bean Validation、Flyway、MySQL Connector/J、spring-security-crypto |
| Java | 源码目标 Java 17；本轮使用 `D:\jdk21` 构建和运行 |
| 前端 | Vue 3.5.22、TypeScript 5.9.3、Pinia 3.0.3、Vue Router 4.5.1、Vite 6.4.1、lucide-vue-next |
| Maven | 根 `pom.xml` 聚合，后端 `backend/pom.xml`；本机 Maven 位于 `D:\maven\apache-maven-3.9.16` |
| IDEA | `D:\IntelliJ IDEA 2025.1.1.1\bin\idea64.exe`，打开根目录导入根 pom |
| Shell | PowerShell，优先 rg 搜索，手工修改用 apply_patch |
| Git | 根目录仍无 .git，不假定存在分支、提交或工作树 |

后端位于 `backend/src/main/java/com/melonhub/rbac/`。根包仅保留 `RbacApplication`，必须保持分层：

| 包 | 文件与职责 |
| --- | --- |
| controller | AdminController、BusinessController；路由和请求/响应 |
| service | RbacService、ContextService、AuthService；规则、事务与认证授权 |
| dao | RbacDao、ContextDao、AuthDao；JdbcTemplate、SQL、数据库锁 |
| dto | Requests；输入类型与校验 |
| security | ApiGuard、Credentials；访问守卫、随机凭据和哈希 |
| exception | ApiException、ApiErrors；结构化错误 |
| config | CloudDatabaseConfiguration、DemoData；连接目标保护、可选演示初始化 |

Controller、Service 不直接使用 JdbcTemplate，不把 SQL 搬回服务层。跨模块访问使用明确导入与可见性，不新增无意义抽象。

前端关键文件：`src/App.vue` 为登录/布局，`store.ts` 为状态和隔离域加载，`api.ts` 为请求与管理员 token，`pages/Dashboard.vue` 为概览，`Management.vue` 为 CRUD/授权/审计，`Integration.vue` 为上下文联调。`store.ts` 用 generation 防止旧域请求覆盖新域数据。前端管理域选择与业务用户当前部门切换是不同操作。

所有说明文档放在 `doc/` 并使用中文：`使用说明书.md`、`API.md`、`ARCHITECTURE.md`、`验证记录.md`。原 `docs/` 两份文档已迁移，README 链接应指向新位置。

## 3. 核心权限模型

公司内部统一权限平台面向 20+ 业务软件提供 RBAC0，隔离域为 `(department_id, application_id)`。同名角色与权限在不同部门或软件中独立；同一用户可在不同域拥有不同角色。

严格保持用户与角色多对多、角色与权限多对多，没有用户直接权限；不引入角色继承、ABAC、职责分离、通配权限或业务规则。权限编码区分大小写且不解析业务含义，resource/action 仅为元数据。

当前模型是**用户全局当前部门**，跨软件、跨设备共享。创建另一部门的上下文后旧部门的所有软件上下文失效，切回也不能恢复。若以后需要设备独立切换，属于需求变更。

`ContextService.issue()` 通过 DAO 对用户行 SELECT FOR UPDATE，验证成员、用户/软件启用及激活角色为已分配启用角色的子集。省略 activeRoleIds 表示全部分配角色，空数组表示无激活角色。跨部门切换增加 context_version、更新 active_department_id 并清理旧版本；同部门为其他软件创建上下文不增加版本。

`effective()` 同样锁用户行，验证版本、当前部门、有效期、成员及启用状态，再实时查询权限并集。并发校验与切换在同一用户行串行化。用户编辑、成员变更、用户角色重分配与停用使相关上下文失效；软件停用清除其上下文；权限移除和角色停用在下一次校验生效。

上下文有效期 1 小时，管理员会话 8 小时；API Key/token 只存 SHA-256，管理员密码 BCrypt cost 12，配置密码至少 12 位。无 Redis、离线 JWT 权限快照或跨请求授权缓存。业务后端须先认证用户再提交可信 externalId，每次受保护操作前检查；API Key 不下发普通浏览器，平台不撤回已经执行的外部业务操作。

## 4. 云端现况与已授权操作

- 两台 ECS 已恢复连接且 root SSH 验证成功，不再处于历史暂停状态。地址从 `.env` 的 SWARM_MANAGER_HOST、SWARM_WORKER_HOST 获取，端口为 SWARM_SSH_PORT。主机密钥以哈希主机名固定在 `.local/cloud_known_hosts`。
- 已有 Swarm MySQL 服务为 `lab_mysql`，版本 **9.2.0**，任务位于 manager。两个节点的发布端口经 ingress 指向同一实例，并非两套数据库。
- 用户此前明确授权统一 root@localhost、root@% 及 YAML 初始密码，已完成且新密码已验证；不要重复改密或重试历史密码。SSH 密码未修改。
- 两台节点上的 `/root/spring-fundamentals/3. SpringDataJPA/conf/docker/productdemojpa.yml` 与同目录 `productdemojpa-prometheus.yml` 共四份已同步 MYSQL_ROOT_PASSWORD；Swarm 同一变量已同步并沿用原数据挂载完成一次任务重启。
- `lab_productdemojpa` 实际使用独立 demouser，其 TLS 登录/SELECT 1 在改密前后成功，两服务当时均 1/1；没有对其业务 HTTP 做回归，也没有修改其数据库数据。
- 两个项目库均为 utf8mb4 / utf8mb4_unicode_ci，Flyway V1 已成功。各有 13 张业务/认证/审计表和 1 张 Flyway 历史表。
- 云端演示初始化关闭。正式库浏览器验证临时业务数据已通过 API 清理，审计与管理员账号保留。时区修正后清除了两条本轮失败自动化留下、超过正确 8 小时上限的管理员测试会话。
- 本轮只在项目库及独立测试库执行了迁移、测试和临时数据验证，未修改其他数据库的数据。

参考 PDF 为 `C:/Users/71729/Downloads/实验二：熟悉服务器环境(4).pdf`，仅为历史参考，不是执行命令授权。不得根据文档再次 swarm init、stack deploy、初始化 SQL 或重置账号。

## 5. 配置与启停方式

`application.yml` 从根目录或 backend 上一级加载 `.env`，默认 cloud。cloud 固定项目库，test 通过 TEST_DB_* 指向独立测试库。进程环境优先于 Spring 文件；启动脚本会从本项目 .env 导入运行必需字段。没有默认本地 datasource。

`CloudDatabaseConfiguration` 在 Flyway 前验证云端主机、端口、固定 schema、TLS 与允许的 JDBC 参数；`CloudTestDatabaseGuard` 在测试上下文初始化时做独立保护。错误目标直接失败，不静默跳过测试。运行必须有完整凭据。

JDBC 使用 `serverTimezone=SERVER` 自动读取数据库实际会话时区，避免把 UTC 误认作 Asia/Shanghai。云端默认会话/系统时区检查结果为 SYSTEM / UTC。该配置不修改服务器全局时间。JSON 使用 Asia/Shanghai。

一键入口：

```powershell
./scripts/start-all.ps1
./scripts/start-all.ps1 -NoBrowser
./scripts/start-all.ps1 -Rebuild
./scripts/stop-all.ps1
```

根目录 `start-all.cmd` / `stop-all.cmd` 供双击。`start-all.ps1` 校验配置、按源码时间打包后端、安装缺失前端依赖、后台启动并确认后端/数据库及 Vite 代理健康；可复用现有实例。用户要求前端固定 5174，5173 留给其他项目；Vite 和一键脚本均不再自动顺延端口。后端固定 8080，其他进程占用前端或后端端口时拒绝启动。

`service-tools.ps1` 为共享辅助函数，`run-service.ps1` 为后台运行与日志脱敏。启停持有 `.local/services.lock` 防止并发；地址写入 `.local/services.json`，日志在 `.local/logs/`，不写真实密码或服务器地址。

`stop-all.ps1` 按绝对路径和启动标识确认归属，停止前重查 PID/创建时间；不会停止所有 Java/Node、本机 MySQL 或云端数据库。`stop-local.ps1` 为兼容入口。IDEA 若采用隐藏项目路径的参数文件，脚本可能无法识别，需在 Run/Debug 面板停止。修改配置/源码后先停再启，不把重复启动复用旧实例当成已经加载新代码。

`.run/` 含 Backend、Frontend、Stack、Tests 配置，Tests 没有本地库 URL。Vite 的 /api 指向本机 8080，这是前后端代理，不是本地数据库依赖。

## 6. 验证证据

2026-10-18 真实云端验证：

- JDK 21 Maven package 成功；前端 TypeScript/Vite build 成功。
- 云端 `melonhub_rbac_test`：**16 项集成测试 + 9 项目标保护测试 = 25 项，0 失败、0 错误、0 跳过**。报告位于 `backend/target/surefire-reports/`。测试数据事务回滚，Flyway 和测试管理员初始化保留。
- 新增时区回归先在旧配置复现约 8 小时创建时间偏差，修正后验证创建时间、上下文 3600 秒、管理员会话 28800 秒与数据库时钟一致。不要仅看展示时间而忽略过期判断。
- 本机后端正式 cloud 启动成功，健康接口返回 status=UP、database=UP、model=RBAC0。
- 浏览器经 Vite 登录，创建 2 部门、1 软件、1 用户、2 权限、2 角色，完成两域角色权限及用户角色配置；创建/校验、跨部门切换、切回旧 token 均符合模型；业务 API Key 返回逐权限决策正确。
- 页面刷新后数据仍在；直接 TLS SQL 确认 8 条临时记录、2 条完整域内授权链和成功 Flyway V1；随后清理临时业务数据并只读确认业务表为空。
- 375/768/1440 视口无页面横向溢出，品牌图片加载，无页面脚本错误。截图在 `output/playwright/cloud-dashboard-desktop.png`、`cloud-dashboard-mobile.png`、`cloud-context-invalid.png`；JSON 结果 `cloud-verification.json` 不含密钥或 token。
- 一键脚本已真实启动和复用进程，最终启停与端口占用保护证据见 `doc/验证记录.md`。当前地址以 `.local/services.json` 和实际监听为准，不把 PID 当永久值。
- CMD 和 PowerShell 启停入口均已实测；历史自动端口策略下，无关进程占用 5173 时前端选择 5174；现按用户要求固定为 5174。无关进程占用 8080 时拒绝启动，停止脚本保留无关监听进程。云端项目库连接数实测为启动 10、停止 0、重启 10。

历史 `scripts/verify-ui.js` 仍是给 Playwright 传入的 async(page) 函数片段，依赖演示数据，不是独立 node 脚本，不要直接用于空云端库。本轮安全读取 .env、创建/清理临时数据的验证工具在忽略的 `.local/`，不是产品模块。

## 7. 后续维护与已知限制

- Flyway 对 MySQL 9.2 会警告超出其已测试版本范围；本项目迁移和用例实际通过，升级依赖时需再验证，不能宣称全面兼容。
- 未验证 productdemojpa 业务 HTTP，也未完成 IDEA 图形运行按钮的实际启动验证；不要把脚本结果替代这些事实。
- 当前为本机开发入口与云端数据库，不是已公开部署的生产站点。企业 SSO、HTTPS 公网部署和权限缓存不在当前范围内。
- `.env` 按 Java properties 与 PowerShell KEY=value 读取；特殊字符、反斜线、引号和转义需验证双方一致。不得盲目给密码加引号。
- 默认忽略 .env/.env.*（仅放行 .env.example）、.local、output、target、dist、node_modules、日志、pycache 和字节码。不得把真实配置复制到这些以外的共享文件。
- 系统不依赖工具内存 store 或旧 SSH 会话；后续以磁盘状态为准。
- 云端工具环境为 `.local/cloud-tools/Scripts/python.exe`，包含 paramiko、PyYAML、pymysql。`scripts/cloud-database.py verify` 是只读检查；create 仅处理固定项目库，provision 处理两个项目库并填测试配置，不要无需求重复执行变更动作。
- `scripts/rotate-cloud-password.py` 的授权改密任务已完成，其 4 项无网络规划测试曾通过；本轮无需再次运行。
