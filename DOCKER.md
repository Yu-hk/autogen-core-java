# Docker 部署

把控制台（React 前端 + Spring-less 的 JDK 后端）打包成一个镜像，本地或服务器一条命令拉起。

> 前端已用 **React + TypeScript + Vite** 重写：`npm run build` 产物落在
> `src/main/resources/static`，由后端 `AgentWebServer` 按 classpath 直出，
> 无需额外的前端服务器。Docker 镜像把"构建前端 → 打 jar → 跑 JRE"三步合成一个多阶段构建。

## 一、目录约定

| 文件 | 作用 |
| --- | --- |
| `Dockerfile` | 四阶段构建：node 构建前端 → maven 跑测试 → 打 jar → eclipse-temurin 以非 root 用户跑 JRE |
| `docker-compose.yml` | 一键编排：端口映射、环境变量、数据卷 |
| `docker-entrypoint.sh` | 入口脚本，支持 `PORT` / `JAVA_OPTS` 覆盖，并对数据目录不可写给出明确告警 |
| `.dockerignore` | 排除 `target/`、`node_modules/`、`agent.local.properties`（密钥）等，保持构建上下文干净 |

## 二、构建镜像

```bash
cd D:\workspace\autogen-core-java

# 构建镜像（首次会下载 node/maven 基础镜像并安装依赖，稍慢）
docker build -t autogen-core-java:latest .
```

构建分四个阶段，最终运行镜像只含 JRE + `app.jar` + `lib/`，体积远小于把整个构建工具链带进去：

| 阶段 | 干什么 | 说明 |
| --- | --- | --- |
| `frontend` | `npm ci` + `npm run build` | 产物落 `src/main/resources/static` |
| `test` | `mvn test` | **测试在镜像构建期就跑**；184 条用例全走 MockChatServer，不需要真模型 |
| `backend` | `mvn package -DskipTests` | 测试已跑过，这里只打包 |
| `runtime` | JRE + jar | 非 root（uid 10001）、只读根文件系统 |

基础镜像都固定到 patch 级 tag（`node:22.11.0-slim` / `maven:3.9.9-eclipse-temurin-21` /
`eclipse-temurin:21.0.5_11-jre`）。浮动 tag 会在某天悄悄换版本，构建结果跟着变、故障无法复现。

只想跳过测试（紧急发布）时：`docker build --build-arg SKIP_TESTS=1 -t autogen-core-java:latest .`

## 三、运行

### 方式 A：docker run

```bash
# 口令必须有：容器绑 0.0.0.0，没配口令服务会直接拒绝启动
export SERVER_AUTH_TOKEN=$(openssl rand -hex 16)

docker run -d --name agentcore \
  -p 8097:8097 \
  -e SERVER_HOST=0.0.0.0 \
  -e SERVER_AUTH_TOKEN="$SERVER_AUTH_TOKEN" \
  -e MODEL_PROVIDER=ollama \
  -e MODEL_NAME=qwen2.5:7b \
  -e MODEL_BASE_URL=http://host.docker.internal:11434/v1 \
  -e TOOL_CODE_ALLOW_SELECT=false \
  -e TOOL_CODE_WORKSPACE=/workspace \
  -v agentcore-data:/data \
  -v "$PWD/workspace:/workspace" \
  autogen-core-java:latest
```

### 方式 B：docker compose（推荐）

```bash
export SERVER_AUTH_TOKEN=$(openssl rand -hex 16)   # 不设会直接报错退出
docker compose up -d --build
```

启动后浏览器打开 `http://localhost:8097`，首次会要求输入口令（就是上面那个值，输一次记在本浏览器）。

## 四、环境变量

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `SERVER_AUTH_TOKEN` | — | **必填**（绑定非回环地址时）。`/api/*` 全部要求 `Authorization: Bearer <token>`；页面会提示输入。留空且绑 0.0.0.0 → 拒绝启动 |
| `SERVER_AUTH_ALLOW_ANONYMOUS` | `false` | 显式放行「对外监听但不鉴权」，只给「前面已有反代做 SSO」用 |
| `WEB_MAX_CONCURRENT_RUNS` | `2` | 同时在跑的任务数，超出的请求立刻 429 |
| `SERVER_HOST` | `0.0.0.0`（镜像内） | 绑定地址；容器里必须是 `0.0.0.0` 才能被宿主机访问 |
| `SERVER_PORT` | `8097` | 服务端口（同时被 `PORT` 覆盖，见入口脚本） |
| `SERVER_SNAPSHOT_DIR` | `/data/snapshots` | 快照落盘目录（断点续跑用） |
| `WEB_DATA_DIR` | `/data` | 会话落库根目录（实际写到 `/data/conversations/<id>.json`）。不挂卷则重启丢上下文 |
| `WEB_LOG_DIR` | `/data/logs` | 结构化日志（`access-*.jsonl` / `run-*.jsonl`） |
| `TOOL_BAIDU_AK` | — | 百度地图 AK。镜像内配置只写 `${TOOL_BAIDU_AK:}` 占位符，真实值从环境注入 |
| `TOOL_AMAP_KEY` | — | 高德 Key，同上 |
| `DEEPSEEK_API_KEY` | — | 云端模型 Key（`MODEL_API_KEY` 的等价写法） |
| `MODEL_PROVIDER` | `ollama` | `ollama` 或 `deepseek` |
| `MODEL_NAME` | `qwen2.5:7b` | 模型名 |
| `MODEL_BASE_URL` | 见 `agent.properties` | 模型 API 地址 |
| `MODEL_API_KEY` | — | 云端模型 Key（DeepSeek 等） |
| `TOOL_CODE_ALLOW_SELECT` | `true` | 是否允许页面选择本地工作目录；**Docker/多人共用建议设 `false`**。开着时也只能选白名单（`TOOL_CODE_WORKSPACE` / 额外可写/只读目录）内的路径 |
| `TOOL_CODE_WORKSPACE` | `target/code-workspace` | 模型可读写的固定目录 |
| `JAVA_OPTS` | `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` | 透传给 JVM。默认值让堆按 cgroup 限额算，OOM 时直接退出让编排重启 |

> 以上变量名与 `AppConfig` 的覆盖规则一一对应：系统属性 `server.host` ＞
> 环境变量 `SERVER_HOST` ＞ 配置文件 `server.host`。详见 `src/main/resources/agent.properties`。

## 五、模型从容器里怎么连

容器是隔离的网络，**不能**直接用 `127.0.0.1` 指向宿主机的 Ollama。两种做法：

1. **本机 Ollama（Docker Desktop）**：把 `MODEL_BASE_URL` 设成
   `http://host.docker.internal:11434/v1`（Docker Desktop 提供的宿主别名）。
2. **云端模型**：直接用 DeepSeek 等，`MODEL_BASE_URL` 填官方地址并配 `MODEL_API_KEY`。

## 六、持久化

一个 `agentcore-data` 卷覆盖三份数据，容器重建都不丢：

| 容器内路径 | 内容 | 丢了会怎样 |
| --- | --- | --- |
| `/data/snapshots` | 任务快照（断点续跑） | 无法从历史快照继续 |
| `/data/conversations` | 会话落库（每段一个 `<id>.json`） | 重启后无法接着上一段对话（页面会当新对话） |
| `/data/logs` | 结构化日志 | 无法按 requestId 追查历史请求 |

`/workspace`：模型的工作目录（`TOOL_CODE_ALLOW_SELECT=false` 时生效），挂宿主机目录后模型写的代码直接可见。

> 卷的属主是镜像里预先 `chown` 过的 uid 10001。如果用**宿主机目录**而非命名卷挂 `/data`，
> 记得先 `chown -R 10001:10001 <目录>`，否则容器里写不进去（入口脚本会打印一行警告）。

## 七、健康检查与排查

```bash
# 服务状态（ok/ready 字段）—— 现在要口令
curl -H "Authorization: Bearer $SERVER_AUTH_TOKEN" http://localhost:8097/api/health

# 指标（Prometheus 文本格式）
curl -H "Authorization: Bearer $SERVER_AUTH_TOKEN" http://localhost:8097/api/metrics

# 按 traceId 追一次请求：页面上响应头 X-Request-Id 就是这个值
docker exec agentcore sh -c 'grep 1a2b3c4d /data/logs/*.jsonl' 

# 容器健康状态（HEALTHCHECK 已内置，会带着口令去探）
docker ps --format 'table {{.Names}}\t{{.Status}}'

# 看容器日志
docker logs -f agentcore

# 重建（改了 Dockerfile 或前端后）
docker compose up -d --build
```

常见问题：

- **页面打不开 / 连接被拒**：确认 `SERVER_HOST=0.0.0.0`，且 `docker ps` 里端口已映射。
- **容器一直重启，日志里是「拒绝启动：…没有设置访问口令」**：设置 `SERVER_AUTH_TOKEN` 后再起；
  前面已有反代做统一登录时，可显式 `SERVER_AUTH_ALLOW_ANONYMOUS=true`。
- **页面一直停在「需要访问口令」**：口令与容器里的 `SERVER_AUTH_TOKEN` 不一致；换个无痕窗口重试。
- **点运行提示「当前已有 N 个任务在跑（上限 2 个）」**：这是并发闸门，不是故障；
  等一个结束，或按机器能力调 `WEB_MAX_CONCURRENT_RUNS`。
- **模型连不上**：容器里 `127.0.0.1` 不是宿主机，改用 `host.docker.internal`（见第五节）。
- **工作目录选择按钮 403**：`TOOL_CODE_ALLOW_SELECT=false` 时属正常，改用固定 `/workspace`。
- **提示「这个目录不在允许范围内」**：页面选目录只允许白名单，把目录加到
  `tool.code.writable-dirs`（可写）或 `tool.code.read-only-dirs`（只读）后重启。
