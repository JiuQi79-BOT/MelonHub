# JaCoCo 测试指南

更新日期：2026-10-26。JaCoCo 随后端测试记录 Java 代码执行情况，生成行、分支等覆盖率。它不会测试已部署的服务器容器，也不包含前端或独立 Java SDK。

## 准备

在项目根目录打开 PowerShell，使用 JDK 21 和 Maven。当前电脑 JDK 为 `D:/jdk21`，其他电脑按实际位置设置。忽略的 `.env` 需要填写 `CLOUD_DB_*`、`TEST_DB_URL/TEST_DB_USERNAME/TEST_DB_PASSWORD`。

测试只能使用云端独立库 `melonhub_rbac_test`，不能连接正式库。测试会写入测试数据并执行迁移，不要与别人共用同一个测试库并发运行。`BACKEND_MODE=server` 不影响测试目标，测试在本机专用进程运行。

## 完整命令

```powershell
$env:JAVA_HOME = 'D:/jdk21'
$env:SPRING_DATASOURCE_HIKARI_MAX_LIFETIME = '60000'
$env:SPRING_DATASOURCE_HIKARI_KEEPALIVE_TIME = '30000'
$env:SPRING_TRANSACTION_DEFAULT_TIMEOUT = '120s'
./scripts/test-backend.ps1 -Coverage
```

三个 Spring 参数只作用于当前终端启动的测试进程，用于云连接回收和较长集成事务，不修改服务器配置。结束后关闭该终端即可；不要继续在同一终端启动本机正式后端并误用这些参数。

脚本执行 `clean verify -Pcoverage`：清理旧产物、挂载 agent、编译执行全部后端测试、打包并生成报告。当前 JaCoCo 0.8.12，本次使用 JDK 21，没有最低覆盖率门槛。

本次耗时 8 分 40 秒，等待 `BUILD SUCCESS` 才表示完整执行成功。出现 `BUILD FAILURE` 时应排查错误，不能用旧报告声称这次通过。

## 打开与理解报告

```powershell
Start-Process ./backend/target/site/jacoco/index.html
```

可逐层进入包、类和源码。红色表示未覆盖，黄色表示部分分支覆盖，绿色表示覆盖；重点关注鉴权、授权和事务等关键路径，而非只看汇总百分比。

| 文件 | 用途 |
| --- | --- |
| `backend/target/site/jacoco/index.html` | 浏览器报告入口 |
| `backend/target/site/jacoco/jacoco.xml` | CI 或分析工具输入 |
| `backend/target/site/jacoco/jacoco.csv` | 汇总统计 |
| `backend/target/surefire-reports/` | 各组测试结果及失败明细 |

报告是忽略的构建产物，下一次 `clean` 会删除。需要交作业时可把整个 `backend/target/site/jacoco/` 目录另存到提交材料目录；单独一个 HTML 文件缺少样式及子页面。

## 本次结果

2026-10-26 一次完整执行：134 项，127 项通过、7 项跳过、0 失败、0 错误。行覆盖率 80.36%，分支 64.79%，指令 72.84%，方法 74.05%，类 88.78%。

Redis 关闭导致权限缓存集成 6 项、治理中的 Redis 全局限流 1 项跳过，不能理解为缓存已验证。覆盖这些路径需要先配置专用测试 Redis 再重跑，不得连接其他应用的 Redis。

只跑普通后端测试、不生成覆盖率时执行 `./scripts/test-backend.ps1`。若发生网络超时，恢复云测试库连接后重跑完整覆盖率；不要绕过数据库目标保护，也不要用正式库替代。
