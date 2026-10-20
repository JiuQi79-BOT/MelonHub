# 使用已有 Docker MySQL 在本机运行

2026-10-18，用户明确选择复用本机已有 MySQL，当前目录为 `D:\MelonHub`。通过 `local` 配置显式启用，与保留的 `cloud` 配置分别管理，连接失败不会自动切换数据库。权限缓存使用本项目专用的 Redis 容器。

## 当前环境

| 组件 | 当前使用方式 |
| --- | --- |
| 前端 | `http://127.0.0.1:5174` |
| 后端 | `http://127.0.0.1:8080`；监控 `http://127.0.0.1:8081/actuator` |
| MySQL | 已有容器 `xmudemo-mysql`，MySQL 8.3.0，发布端口 3306，连接要求 TLS |
| 数据卷 | 复用原有 `xmudemo_db_data`，不重建、不清空 |
| 项目库 / 测试库 | `melonhub_rbac` / `melonhub_rbac_test`，与旧项目数据库分开 |
| Redis | 专用容器 `melonhub-redis`（`redis:7-alpine`，标签 `com.melonhub.role=cache`），只发布到 `127.0.0.1:6380`，要求密码，无持久化 |
| Java | `C:\Program Files\Java\jdk-17` |
| Maven | 优先 PATH/MAVEN_HOME/M2_HOME，其次自动发现 `.m2/wrapper/dists` 中已安装版本 |

Docker 中名为 `backend` 的 Compose 项目实际使用 PostgreSQL；本项目复用的是另一组现有服务中的 `xmudemo-mysql`。只拉起指定的 MySQL 容器，不启动旧项目应用及其他容器。Docker 中其他项目的 Redis 容器与本项目无关，脚本不会使用或修改它们。

## 启动与停止

先启动 Docker Desktop，再双击根目录 `start-all.cmd`，或执行：

```powershell
./scripts/start-all.ps1 -NoBrowser
```

脚本读取 `.env` 后依次：

1. 发现指定 MySQL 容器处于停止状态时启动它。
2. 生成 `.local/redis/redis.conf`；`melonhub-redis` 不存在时创建（首次需要拉取 `redis:7-alpine` 镜像），已停止时启动，配置变化时重启。同名容器若没有本项目标签，脚本拒绝操作并报错。
3. 按需打包后端、安装缺失的前端依赖，启动前后端，确认后端、数据库与前端代理健康后报告就绪。

访问 `http://127.0.0.1:5174`，使用 `.env` 中的 `ADMIN_USERNAME` 和 `ADMIN_PASSWORD` 登录。云端业务数据不会自动复制到本机。

停止项目使用 `stop-all.cmd` 或：

```powershell
./scripts/stop-all.ps1
```

停止脚本停止本项目 Java/Vite 进程和带本项目标签的 Redis 容器，保留共享 MySQL 容器与数据。修改配置或源码后先停再启。

## 配置与数据库边界

真实凭据只保存在 Git 忽略的根目录 `.env`。示例字段见根目录 `.env.example`：

| 配置 | 本机要求 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `local` |
| `LOCAL_DB_HOST` | `127.0.0.1` 或 `localhost` |
| `LOCAL_DB_PORT` | 现有容器的宿主机映射端口，本机为 3306 |
| `LOCAL_DB_NAME` | 固定 `melonhub_rbac` |
| `LOCAL_DB_USERNAME/PASSWORD` | 现有 MySQL 账号，本机按用户要求使用 root |
| `LOCAL_DB_CONTAINER` | 可选，指定一键启动时可拉起的已有容器，本机为 `xmudemo-mysql` |
| `CACHE_ENABLED` | `true`；设为 `false` 时不启动 Redis，后端每次校验都查询 MySQL，限流也只按实例计算 |
| `REDIS_HOST/PORT` | `127.0.0.1` / `6380`；只有回环地址时脚本才会管理容器 |
| `REDIS_PASSWORD` | 自行生成的随机长字符串；留空时启动脚本报错 |
| `REDIS_CONTAINER` | `melonhub-redis` |
| `TEST_DB_TARGET` | `local` |
| `TEST_DB_URL` | 同一入口的 `melonhub_rbac_test`，保留 `sslMode=REQUIRED` 和 `serverTimezone=SERVER` |
| `TEST_DB_USERNAME/PASSWORD` | 测试库访问凭据 |

`.local/redis/redis.conf` 中含有 Redis 密码，与 `.env` 一样不能分享或提交。修改 `REDIS_PASSWORD` 后重新运行启动脚本，容器会按新配置重启。

首次配置新的 MySQL 实例时，仅创建这两个专用库，字符集 `utf8mb4`、排序规则 `utf8mb4_unicode_ci`；表由 Flyway 管理（当前为 V1–V5）。不能改用旧项目的库名。

应用与测试会在 Flyway 前校验所选入口、端口、固定库名、TLS 和 JDBC 选项。`local` 只允许回环地址；同时启用 `cloud` 和 `local` 会失败；测试误指向正式库会失败。

恢复云端运行时，先停项目，把 `SPRING_PROFILES_ACTIVE` 改为 `cloud` 并核对保留的 `CLOUD_DB_*`。云端测试另需把 `TEST_DB_TARGET` 改为 `cloud`，同步修改 `TEST_DB_*`。本机与云端的数据库内容相互独立。

## 前端依赖与构建

一键脚本使用 `npm ci --ignore-scripts` 安装前端依赖。本机 PATH 超过 8191 个字符时，npm 通过 `cmd.exe` 运行的生命周期脚本会找不到 node，跳过安装脚本可以避开这个问题，且本项目依赖不需要安装脚本。

手动执行 `npm --prefix frontend run build` 如果报告找不到 `node`，同样是 PATH 过长造成的，可以只在当前终端缩短 PATH 后重试：

```powershell
$env:Path = (Split-Path (Get-Command node).Source) + ';' + $env:SystemRoot + '\System32'
npm --prefix frontend run build
```

## 验证与排查

```powershell
./scripts/test-backend.ps1
Invoke-RestMethod http://127.0.0.1:8080/api/health
Invoke-RestMethod http://127.0.0.1:5174/api/health
```

健康响应应包含 `status=UP`、`database=UP`、`cache=UP`、`model=RBAC0`。`cache=DOWN` 表示 Redis 不可用，服务已回退到 MySQL；`cache=RECOVERING` 表示刚恢复，几秒内会转为 `UP`。本机测试数量和本轮验证范围见 [验证记录](验证记录.md)。

| 现象 | 排查 |
| --- | --- |
| 报告 MySQL 容器不可用 | 检查 Docker Desktop 是否运行、容器是否存在；不要停止不明进程或删除旧数据库 |
| 报告 3306 或 6380 绑定失败 | 检查端口占用，确认占用者后再处理 |
| `REDIS_CONTAINER names a container that is not the MelonHub cache` | 同名容器不属于本项目，换一个 `REDIS_CONTAINER` 名称，不要删除该容器 |
| `The MelonHub Redis cache did not open its port` | 用 `docker logs melonhub-redis` 查看原因 |

日志位于 `.local/logs/`，密码与配置的服务器地址经过脱敏。

IDEA 的 Backend 配置会读取根目录 `.env`，可使用同一套 local 配置。启动前确保 MySQL 与 Redis 容器已运行；本轮实际验证使用脚本与浏览器，未验证 IDEA 图形运行按钮。
