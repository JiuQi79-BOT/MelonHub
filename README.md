# MelonHub 统一权限平台

企业内部统一权限服务，为 20 多个业务软件提供配置与在线授权判断。软件隔离角色和权限：同一软件的角色共享权限定义，权限编码在软件内唯一；部门只用于人员管理、筛选和批量设置软件角色。

技术栈：Spring Boot 3.5、Spring JDBC、Flyway、云 MySQL、Vue 3、TypeScript、Ant Design Vue 4。Redis 客户端保留，默认关闭，等待服务器部署后启用；MySQL 是唯一持久数据源。

## 主要能力

- 软件角色、共享权限、用户多角色与分配期限；在线上下文与批量校验。
- 部门跨页选人、多软件批量追加角色，保留已有有效分配及期限。
- 角色申请审批、权限复核、权限反查、即将到期报告、用户导入和软件级 v3 权限清单。
- 多 API Key、签名 Webhook 与事务外盒、审计哈希链、分级管理员与 TOTP 两步验证。
- 14 页控制台、明暗主题、手机适配、全局搜索、健康检查与监控。
- 可选 Redis 授权缓存、全局限流与故障回退；Java 业务侧 SDK。

## 文档

- [服务器部署与启动](docs/服务器部署与启动.md)：已部署后端、本机启动、更新和服务器管理。
- [JaCoCo 测试指南](docs/JaCoCo测试指南.md)：完整命令、报告查看和跳过项说明。
- [项目讲解与数据链路](docs/项目讲解与数据链路.md)：从请求、对象映射到页面更新，含设计理由和答辩问答。
- [详细设计](docs/详细设计.md)：需求追踪、逐类职责、前端与 SDK、事务并发及设计边界。
- [数据库设计](docs/数据库设计.md)：当前关系、唯一键和复合外键、V7 共享权限迁移。
- [使用说明书](docs/使用说明书.md)：控制台与授权工作流。
- [云数据库运行](docs/云数据库运行.md)：配置、启停、IDEA 和测试。
- [接口说明](docs/API.md)、[架构说明](docs/ARCHITECTURE.md)。
- [验证记录](docs/验证记录.md)、[Java SDK](sdk/java/README.md)。

## 快速使用

当前运行方式为 **服务器后端 + 本机前端 + SSH 加密通道**，本机 `.env` 已设置 `BACKEND_MODE=server`。后端部署后在服务器持续运行，不依赖本机电脑；日常启动不会重复部署后端。

1. 在项目根目录双击 `start-all.cmd`，等待启动成功；脚本启动本机前端和 SSH 通道。
2. 访问 `http://127.0.0.1:5174`，使用原有管理员账号登录。
3. 用完双击 `stop-all.cmd`，停止本机前端和 SSH 通道。服务器后端继续运行，关闭电脑也不会停止它。
4. 下次使用时再次双击 `start-all.cmd` 即可，电脑需能联网连接服务器。

也可在项目根目录的 PowerShell 中执行：

```powershell
# 日常启动，不自动打开浏览器
./scripts/start-all.ps1 -NoBrowser

# 停止本机前端和 SSH 通道，不停止服务器后端
./scripts/stop-all.ps1
```

服务器模式下不需要在 IDEA 启动本机 Java，也不需要手动启动 MySQL 或 Docker。本机 8080 用于 SSH 通道，不要同时启动占用该端口的本机后端。前端 5174、本机 API 8080 和服务器 API 18080 均绑定各自回环地址，服务器 API 经 SSH 加密访问。

### 更新代码与管理后端

| 场景 | 操作 |
| --- | --- |
| 修改前端页面 | 本机项目启动后由 Vite 自动更新，必要时刷新浏览器 |
| 修改后端 Java 代码 | 先测试，再执行 `./scripts/deploy-backend.ps1` 构建并更新服务器；仅重启本机项目不会部署新代码 |
| 修改本机 `.env` | 先执行 `./scripts/stop-all.ps1`，再启动；更改服务器运行配置还需重新部署 |
| 查看服务器后端状态 | `./scripts/server-backend.ps1 status` |
| 重启服务器后端 | `./scripts/server-backend.ps1 restart` |
| 主动停止服务器后端 | `./scripts/server-backend.ps1 stop`，此时控制台和业务 API 暂时不可用 |
| 启动已停止的服务器后端 | `./scripts/server-backend.ps1 start`，稍后查询状态；本机启动脚本不会代替这一步 |

容器已设置重启策略，服务器 Docker 已启用开机启动；手动停止服务器后端后需要显式启动。服务器模式更新后端使用部署脚本，不使用 `start-all -Rebuild`。完整说明见[服务器部署与启动](docs/服务器部署与启动.md)。

### 首次配置与本机调试

新电脑按 `.env.example` 配置忽略的根目录 `.env`，并按[启动指南](docs/服务器部署与启动.md)安装 Node.js/npm 和 Python/Paramiko、填写 SSH 配置及设置 `BACKEND_MODE=server`。首次前端依赖安装可执行 `npm --prefix frontend ci --ignore-scripts`。初始管理员密码 12–100 字符且 UTF-8 不超过 72 字节。

需要本机 Java 调试时，先停止本机项目，将 `BACKEND_MODE` 改为 `local` 再启动；此模式和本机构建、测试需要 Java/Maven。IDEA 配置见 `.run/`，图形运行按钮未实测。启停脚本不管理 MySQL/Redis 容器，不修改其他应用。

## 数据库与升级

正式库 `melonhub_rbac`、测试库 `melonhub_rbac_test` 均为云端入口，强制 TLS。Flyway V1–V6 保留，新增 V7 将同软件同编码权限合并为共享定义，保留角色关联、用户分配与期限，重复组完整归档；复合外键只允许同软件角色关联权限。升级前备份，细节见数据库设计文档。

`CACHE_ENABLED=false` 为当前默认值。服务器 Redis 部署后填写 `REDIS_*` 再启用。缓存关闭时访问云 MySQL，限流按实例执行；没有本地数据库回退。

## 验证

```powershell
./scripts/test-backend.ps1
./scripts/test-backend.ps1 -Coverage  # JaCoCo，建议使用 JDK 21
npm --prefix frontend test
npm --prefix frontend run build
mvn -f sdk/java/pom.xml -B -ntp test
Invoke-RestMethod http://127.0.0.1:8080/api/health
```

本次证据与未验证范围见 [验证记录](docs/验证记录.md)。历史本地容器、压测数字不能作为本次云端与服务器 Redis 的验收结果。

覆盖率入口执行 clean verify，重新运行后端测试并生成 `backend/target/site/jacoco/index.html`（HTML）及同目录 `jacoco.xml`、`jacoco.csv`。当前 JaCoCo 0.8.12，覆盖率运行使用 JDK 21（可先设置 `$env:JAVA_HOME`）；Redis 关闭时相关用例跳过，需结合测试统计阅读覆盖率。

## 关键约定

- 部门变化不影响软件角色或已有权限上下文；权限由已激活且未到期的软件角色决定。
- API Key 只保存在业务后端；业务后端先认证用户再提交可信 externalId。
- 每次受保护操作前在线校验，网络故障按拒绝处理；平台不撤回已经执行的业务操作。
- Redis 仅用于缓存和限流；Webhook 接收方先验签再清理缓存。
- Controller、Service 不直接写 SQL；已应用迁移不修改。
- `.env`、`.local`、`output`、构建产物均忽略，不提交凭据。
