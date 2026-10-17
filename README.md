# MelonHub 统一权限平台

Vue + TypeScript + Spring Boot + MySQL 的内部统一权限服务，按 **部门 × 软件** 隔离，严格保持 RBAC0：用户与角色、角色与权限均为多对多，没有用户直接权限、角色继承或业务逻辑。

## 快速使用

- 双击根目录 [start-all.cmd](start-all.cmd) 一键启动前后端，检查云数据库与代理后自动打开浏览器。
- 双击 [stop-all.cmd](stop-all.cmd) 停止本项目服务，保留云端 MySQL 和其他程序。
- 完整操作流程、各模块目的、授权示例和问题处理见 **[项目使用说明书](doc/使用说明书.md)**。
- 接口开发见 [中文 API 说明](doc/API.md)，分层与权限原理见 [中文架构说明](doc/ARCHITECTURE.md)。
- 实测范围和结果见 [验证记录](doc/验证记录.md)。

命令行可执行 `./scripts/start-all.ps1 -NoBrowser` 和 `./scripts/stop-all.ps1`。前端固定地址为 `http://127.0.0.1:5174`，端口被其他程序占用时明确报错，不自动切换；后台日志在 `.local/logs/`。启动器会复用健康实例，源码或配置更新后先停再启；`-Rebuild` 可强制打包后端。

启动后端会建立云端数据库连接，停止后端会释放连接，保存的数据保留。已实测项目库连接数为启动后 10、停止后 0、重启后 10；云端 MySQL 本身继续运行。

## 在 IDEA 中运行

1. 打开项目根目录 `D:\PersonalStudy\MelonHub`，导入根目录 `pom.xml`。推荐 SDK 为 `D:\jdk21`，源码编译目标为 Java 17。
2. 根据 `.env.example` 配置根目录 `.env`。本机已保存有效的云端连接和管理员凭据；不要将该文件提交、分享或打印到日志。管理员密码至少 12 位。
3. 首次运行执行 `npm --prefix frontend install`。
4. 选择 **MelonHub Backend** 启动或调试后端；选择 **MelonHub Frontend** 启动前端。支持 npm 的 IDEA 可用 **MelonHub Stack** 同时启动两者。通过 Run/Debug 面板停止或重启。
5. 打开 `http://127.0.0.1:5174`，使用 `.env` 的 `ADMIN_USERNAME` 和 `ADMIN_PASSWORD` 登录。前端固定使用 5174，被占用时先处理冲突再启动。

**MelonHub Tests** 使用独立云端测试库。共享运行配置位于 `.run/`，不含密码或本地测试库 URL。应用从根目录或 backend 工作目录加载 `.env`，已有进程环境变量优先；排查时注意 IDEA 是否继承了旧变量。

命令行启动的预览可用 `./scripts/stop-all.ps1` 停止，旧 `stop-local.ps1` 兼容转到该入口。脚本只停止能够确认属于本项目的进程；无法识别路径的 IDEA 实例请在 Run/Debug 面板管理。避免重复占用 8080。

## 云端数据库

运行环境默认使用 `cloud` 配置，连接已有华为云 Swarm 中的 MySQL **9.2.0**，强制 TLS，不依赖本机 MySQL。两台节点的发布端口指向同一个数据库实例。

- 正式项目库：`melonhub_rbac`。
- 独立测试库：`melonhub_rbac_test`。
- 两库使用 `utf8mb4` / `utf8mb4_unicode_ci`，Flyway V1 创建 13 张业务/认证/审计表及 1 张迁移历史表。
- 不重新初始化已有 MySQL，不执行实验初始化 SQL，不修改其他应用的数据。
- 演示数据关闭：`SEED_DEMO=false`。空库通过控制台创建部门、软件和用户。

| 字段 | 用途 |
| --- | --- |
| `CLOUD_DB_HOST/PORT/NAME` | 云端入口和固定项目库名 |
| `CLOUD_DB_USERNAME/PASSWORD` | MySQL 凭据，当前按用户要求使用 root |
| `SWARM_MANAGER_HOST/SWARM_WORKER_HOST` | 集群节点地址，仅诊断脚本使用 |
| `SWARM_SSH_USERNAME/PASSWORD/PORT` | SSH 凭据，与数据库密码分别配置 |
| `SWARM_CONFIG_PATH` | 已有部署配置路径 |
| `TEST_DB_URL/USERNAME/PASSWORD` | 同一云端实例上的独立测试库，URL 必须带 TLS |

`.env.example` 只有占位值。`application-cloud.yml` 不含真实地址或凭据。运行前保护会核对配置的云端主机、端口、固定库名和 TLS；错误配置会在 Flyway 前停止，不回退本地数据库。

## 命令与验证

```powershell
./scripts/check-cloud-database.ps1
mvn -f backend/pom.xml package -DskipTests
./scripts/start-backend.ps1
```

另一终端启动前端：

```powershell
npm --prefix frontend run dev
```

测试和前端构建：

```powershell
./scripts/test-backend.ps1
npm --prefix frontend run build
```

连接检查是只读操作。测试脚本与 IDEA JUnit 均只允许云端 `melonhub_rbac_test`，目标不符合要求时直接失败。16 项集成测试的业务数据随事务回滚；Flyway 和测试管理员初始化保留在测试库。

2026-10-18 已完成 JDK 21 后端打包、前端生产构建和云端 **25 项测试（16 项集成 + 9 项配置保护），0 失败、0 错误、0 跳过**。后端健康接口 `http://127.0.0.1:8080/api/health` 返回 `status=UP`、`database=UP`、`model=RBAC0`。浏览器新增与授权、部门切换、直接云端 SQL 回读及清理均已验证。

JDBC 使用 `serverTimezone=SERVER` 读取实际会话时区，修复审计显示偏移与数据库过期判断偏差，不修改 Linux 或 MySQL 全局时区。回归用例确认创建时间、上下文 1 小时及管理员会话 8 小时的实际数据库期限。

当前 Flyway 对 MySQL 9.2 会输出已测试版本范围警告；实际 V1 迁移及本项目测试已通过，不能据此宣称其全部功能与该版本完全兼容。

## 目录与行为

后端根包 `com.melonhub.rbac` 仅保留启动入口，按职责分层：

| 包 | 职责 |
| --- | --- |
| `controller` | HTTP 参数、响应和路由 |
| `service` | RBAC0 规则、事务、上下文失效和认证流程 |
| `dao` | JDBC、SQL、持久化与数据库锁 |
| `dto` | 请求类型与校验 |
| `security` | API 守卫、凭据生成与哈希 |
| `exception` | 错误类型与统一响应 |
| `config` | 数据库目标保护、可选演示初始化 |

前端位于 `frontend/src`，迁移位于 `backend/src/main/resources/db/migration`，脚本位于 `scripts`。`.local`、`output`、`target`、`dist` 为忽略的工具环境、验证产物或构建产物。

用户的当前部门是全局状态，跨软件、跨设备共享。切换部门后，该用户旧部门上下文立即失效；切回也不会恢复旧 token。每次权限校验实时查询关联关系，业务软件必须在每次受保护操作前校验。前端 `/api` 代理到本机后端 8080，后端再通过 TLS 访问云端数据库。

详见 [项目使用说明书](doc/使用说明书.md)、[架构与信任边界](doc/ARCHITECTURE.md) 和 [业务 API](doc/API.md)。
