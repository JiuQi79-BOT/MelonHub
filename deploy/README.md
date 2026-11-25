# MelonHub Docker Swarm 部署

验收环境运行在现有 Docker Swarm。MelonHub 前端入口为服务器的 `5174`，后端 HTTP 为 `18080`，Actuator 为 `18081`。Nginx 将 `/api/` 和 `/auth/` 转发到 `melonhub-backend:8080`，同时保留浏览器访问的主机和端口，供后端判断请求来源。后端经 `javaee-proxy` 连接现有 `lab_mysql` 的 `melonhub_rbac`。当前 `CACHE_ENABLED=false`，授权由 MySQL 校验。浏览器直接打开服务器入口。

## 构建镜像

在项目根目录打包应用。

```powershell
mvn -f backend/pom.xml -DskipTests package
npm --prefix frontend ci --ignore-scripts
npm --prefix frontend run build
```

在 Docker 主机上将 `BACKEND_IMAGE` 和 `FRONTEND_IMAGE` 设置为本次发布选定的镜像标签，再构建需要发布的镜像。只发布前端时可以复用当前后端镜像。前端镜像包含生产构建与 `frontend/nginx.conf`。镜像在当前环境由 Swarm 管理节点本地保存，任务若要调度到其他节点，先确保那些节点也能取得对应镜像。

```bash
docker build -t "$BACKEND_IMAGE" backend
docker build -t "$FRONTEND_IMAGE" frontend
```

## 更新服务

服务器部署目录的私有 `.env` 保存 `BACKEND_IMAGE` 和 `FRONTEND_IMAGE` 以及运行配置。新建部署配置用 [env.example](env.example)，每次更新将实际发布的镜像写回私有配置。以 [stack.yml](stack.yml) 为模板重新生成解析后的清单，文件含凭据，保存在部署目录内。

只更新前端时，在管理节点的部署目录保存新 `FRONTEND_IMAGE`，使用以下命令载入配置、刷新解析后的清单并更新该服务。服务器已配置经过同源 API 代理检查的健康探测。

```bash
set -a
. ./.env
set +a
(umask 077 && docker stack config -c stack.yml > stack-resolved.yml)
docker service update --no-resolve-image --image "$FRONTEND_IMAGE" melonhub_melonhub-frontend
```

首次部署或获准更新整个 MelonHub Stack 时，在管理节点的部署目录执行以下命令。现有环境使用共享的 `javaee-proxy` 网络。

```bash
set -a
. ./.env
set +a
docker network inspect javaee-proxy >/dev/null
(umask 077 && docker stack config -c stack.yml > stack-resolved.yml)
docker stack deploy --resolve-image never -c stack.yml melonhub
```

## 发布验证

```bash
docker service ls
curl -fsS http://127.0.0.1:18081/actuator/health
curl -fsSi http://127.0.0.1:5174/api/health
curl -sSi -H 'Origin: http://127.0.0.1:5174' -H 'Content-Type: application/json' -d '{}' http://127.0.0.1:5174/api/auth/login
```

验收时核对 MelonHub 和课程系统的四个前后端服务均为 `1/1`。前端代理的健康请求应返回 JSON 和 `X-Request-Id`，带同源 `Origin` 的空登录请求应返回 `400` 和 JSON。再通过控制台实际登录并打开用户列表。当前四服务镜像和验证结果见 [验证记录](../doc/验证记录.md)。

课程后端的统一身份地址指向服务器 `18080`。课程页面通过 `18090` 的同源代理访问菜单权限和部门查询。登录与配置核对见 [统一身份联调](../doc/统一身份联调.md)。

## 请求日志

```bash
docker service logs --since 2m --timestamps -f melonhub_melonhub-backend
```

在浏览器的网络面板获取菜单请求的 `X-Request-Id`，用同一编号对应后端日志。演示路径见 [双系统操作手册](../doc/双系统操作手册.md#4-菜单请求日志演示)。
