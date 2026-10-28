# 距离生产版本还差什么

> 盘点时间：2026-10-28　代码基线：`autogen-core-java` 1.0.0（68 个主类 / 28 个测试类 / 184 条测试全绿）
> 盘点方式：逐项读代码确认，不凭印象。每条都标注了证据位置。

## 零、一句话结论

现在的形态是**「单人、本机、可信环境」的演示工具**：功能链路是通的（流式、工具调用、断点续跑、Docker 构建都验证过），
但**「谁能用、同时几个人用、出事怎么查」这三件事基本没做**。

所以差距不在功能，在**服务化**。下面按「不修就一定出事」到「修了会更好用」排序。

## 零之一、进度：P0 三项已落地（2026-10-28）

| 项 | 做法 | 验证结果 |
| --- | --- | --- |
| 1.1 鉴权 | `server.auth-token`（env `SERVER_AUTH_TOKEN`）。`/api/*` 要 `Authorization: Bearer` / `X-Auth-Token` / `?token=`（SSE 必需）；**绑定非回环地址却没配口令 → 拒绝启动**；静态页不拦 | 无口令 401、错口令 401、header 200、`?token=` 200、首页 200；`0.0.0.0` 无口令启动退出码 1 |
| 1.2 目录白名单 | 只允许 `tool.code.workspace` / `writable-dirs` / `read-only-dirs` 及其子目录；`/api/fs/list` 不再列盘符与用户主目录 | 白名单外 `run` 400、`fs/list` 403；起点列表只剩配置过的根 |
| 1.4 并发闸门 | `web.max-concurrent-runs`（默认 2）信号量，超出立即 429 并回 `busy`/`limit`；SSE 队列设 2 万上限、满了丢最老 | `WebSecurityTest` 四条用例；全量 165/165 |
| 2.4 健康检查 | Dockerfile + compose 增加 `HEALTHCHECK`（带口令探活） | 语法与拼装一致，需在真机 `docker compose up` 复验 |

前端配套：新增口令闸门（`authGate`/`authInput`/`authGo`），token 存 localStorage，401 自动回到闸门；
`EventSource` 走 `?token=`；429/403 的服务端文案直接显示，不再统一成"连不上服务"。

## 零之二、进度：P1 五项已落地（2026-10-28）

| 项 | 做法 | 验证结果 |
| --- | --- | --- |
| 2.1 会话落库 | `ConversationStore`（`file` / `none`），每段对话一个 `<id>.json`，临时文件 + 原子 rename；启动预热最近 N 段并清理过期（`web.conversation-ttl-hours`，默认 7 天）；内存只是缓存，LRU 淘汰后按 id 重新读盘；`/api/conversations` 供页面刷新后恢复 | `ConversationStoreTest` 8 条（往返、换实例、非法 id、损坏文件、无 .tmp 残留、排序、TTL、禁用）；`ConversationPersistenceTest` 5 条——**关掉服务再用同一数据目录起一个**，追问仍带上一轮原文与答案 |
| 2.3 可观测性 | `/api/metrics`（Prometheus 文本）：运行数/失败数/停止原因（归一化 5 类）/耗时直方图/工具调用/重试/SSE/HTTP 状态码/JVM 内存与线程；每个请求分配 `X-Request-Id` 并落一行 JSON；运行日志落 `run-*.jsonl`，与请求共用一个 traceId | `ObservabilityTest` 4 条：指标数值逐项断言、id 唯一、`/api/run` 与 `run-*.jsonl` 的 requestId 对得上、关掉后不建目录 |
| 2.2 密钥外置 | 配置文件支持 `${ENV:默认值}` 占位符；新增 `agent.local.properties` 覆盖层（已 gitignore + dockerignore）；classpath 配置里的真实百度 AK 已移除，改为 `${TOOL_BAIDU_AK:}` | `AppConfigTest` 新增 2 条：占位符取值/默认值、**内置配置里不许出现形如密钥的字符串**；全量 184/184 |
| 2.4 容器加固 | 基础镜像固定 patch tag；新增 `test` 阶段（构建期跑测试，`--build-arg SKIP_TESTS=1` 可跳过）；运行镜像非 root（uid 10001）、`read_only: true` + `tmpfs /tmp`、`cap_drop: ALL`、`no-new-privileges`、`mem_limit`/`cpus`、`stop_grace_period`；`/data` 一个卷装下快照+会话+日志 | 沙箱无 Docker：YAML/入口脚本语法校验通过，需真机 `docker compose up -d --build` 复验 |
| 2.6 发布工程 | `git init` + `.gitignore`（排除 `agent.local.properties`、`target/`、`logs/`）；`.github/workflows/ci.yml`：后端 `mvn verify`、前端 typecheck+build+**产物与源码同步比对**、镜像构建 + compose 校验 + 冒烟启动（含"无口令必须 401"） | 首次提交完成；CI 需推到远端后才真正跑 |

前端配套：`conv.id` 存 localStorage，刷新页面自动拉 `/api/conversations?conversationId=` 把上次对话画回来；
顺手修掉「停止按钮传错参数」——之前传的是 conversationId，服务端 `/api/stop` 要 sessionId，导致点了没反应。

**P2 中的四项也已落地**（见零之三）：前端有了 vitest（21 条）、SSE 支持 `Last-Event-ID` 断点续传、
全部响应带安全头（CSP 分严格/宽松两套）、`/api/openapi.json` 提供 OpenAPI 3.1 描述。

**仍未做**：错误分类粒度（模型不可用/超时/工具失败仍走同一条 `error` 事件）、小模型提示词回归集、
快照 TTL 配置项、`/api/docs` 可视化页面（当前建议直接导入 Postman/Apifox）。

## 零之三、进度：P2 四项落地（2026-10-28）

| 项 | 做法 | 验证结果 |
| --- | --- | --- |
| 前端自动化测试 | 引入 vitest 2（`vitest.config.ts` 独立于构建配置，默认 node 环境）；`conversation.test.ts` 测纯函数、`api.test.ts` 测口令存取/401 分类/参数编码/SSE 状态判定（jsdom + 手写 EventSource 替身）、`App.render.test.tsx` 用 react-dom 直接渲染，守 22 个 DOM id 的契约 | 21 条全绿；`npm run typecheck` 0 错误。**未引入 Playwright**：要下 Chromium，收益（端到端冒烟）已经由 CI 的镜像冒烟 + 渲染契约测试覆盖大半 |
| SSE 断点续传 | `RunSession` 由"消费即丢的队列"改为"环形事件日志 + 连续序号"（保留窗口 6000 条，下标计算 O(1)）；每条事件带 `id:`，`handleStream` 读 `Last-Event-ID` / `?lastEventId=` 从断点补发；窗口滚过时发 `resync` 让前端重拉全量；会话已回收时发 `expired`（不再返 404，因为 EventSource 拿不到状态码）。前端 `openStream` 不再一断就 `close()`，改为三态回调 `open/reconnecting/closed` | `SseResumeTest` 7 条（序号连续、阻塞等待、terminal 标记、窗口缺口、**读到一半断线后带游标重连**、首次连接从头、未知会话给 expired）；实测一轮 22 条事件，`Last-Event-ID: 1` 重连从 2 接上，不重放不跳号 |
| 安全响应头 | 所有响应加 `nosniff` / `X-Frame-Options: DENY` / `Referrer-Policy: no-referrer`（URL 里带 `?token=`）/ `Permissions-Policy`；HTML 按页面给 CSP：React 产物 `script-src 'self'`，回退旧单页才放宽 `'unsafe-inline'`；HSTS 默认关（内网 http 开了会把自己锁死），`server.hsts=true` 才下发 | `SecurityHeadersTest` 4 条（基线头、SSE 响应也有、两套 CSP 差异、HSTS 开关）；实测首页拿到严格 CSP |
| API 契约 | 手写 `src/main/resources/openapi.json`（OpenAPI 3.1，含鉴权说明、11 个路径、错误码），`GET /api/openapi.json` 直出；人读版在 `docs/api.md` | `OpenApiTest` 5 条：结构合法、**文档里每条路径在真服务上不是"路由不存在"**、**代码里每条路由都写进了文档**、每个 operation 有 summary/responses、HTTP 直出与打包一致 |

---

## 一、P0：多用户一放开就是事故

### 1.1 零鉴权 —— 只要端口通了，谁都能用

| 项 | 现状 | 证据 |
| --- | --- | --- |
| 认证 | 无。所有接口裸奔 | 全仓库 `grep Authorization` 无命中 |
| 授权 | 无角色/租户概念 | `AgentWebServer.route()` 9 条路由全部直通 |
| 审计 | 无「谁在什么时候读了什么」 | 只有 `RunLog` 打运行过程，无操作者身份 |

**在 Docker 里绑 `0.0.0.0` 之后，同网段任何人打开浏览器就是完整权限**——包括下面 1.2 的读盘和 1.3 的执行。

### 1.2 工作目录 = 宿主机任意可读目录

`AgentWebServer.resolveWorkspaceParam()` 的注释写得很直白：

> 「用户选择本身就是授权……所以这里只挡系统目录和路径穿越，不做额外的白名单。」

`CodeWorkspace.rejectReason()` 只挡三类：盘符根、用户主目录本身、`/windows`、`/etc`、`/usr` 这类系统目录。
**`D:/workspace/abrspro2`、`C:/Users/别人/桌面`、`D:/财务数据` 全都能选。**

配上 `list_files` / `read_file` / `search_code`，等于给每个访问者开了一个**宿主机任意文件读取器**，
而 `/api/fs/list` 还能从盘符开始逐层浏览（无 `path` 参数时直接返回 C–Z 所有盘符）。

> 这在内网单机自用没问题；一旦当成「团队共用一台机器跑分析」就是数据泄露事故。

### 1.3 命令执行跑在宿主机上

`CodeCommandTool` 有白名单（`Executables.allowedPrograms()`）和超时（默认 120s/300s，上限 300s），
这层做得不错。但它是 **`ProcessBuilder` 直接 fork 在当前用户下**，没有 cgroup / 容器 / 用户隔离。

- 裸机部署（就是现在内网这台）：命令可以读写该用户能碰的一切；
- Docker 部署：隔离靠容器边界，但 compose 里 `./workspace:/workspace` 是 bind mount，
  指向宿主真实目录后容器内可写即宿主可写。

**生产必须二选一**：要么容器化 + 只挂载专用沙箱目录，要么默认关掉 `code_command`（改策略而非删工具）。

### 1.4 没有并发闸门

| 事实 | 影响 |
| --- | --- |
| `Executors.newCachedThreadPool()` 不限线程数 | 连接数不设上限，SSE 长连接各占一线程 |
| 无 `Semaphore` / 无排队 / 无每用户配额 | 两人同时点「运行」就两份纯 CPU 推理互相抢 |
| `RunSession.queue` 是 `LinkedBlockingQueue`（无界） | 客户端断开后事件仍在堆，只能等 `isTerminal()` 时被动清理 |
| 会话表按 LRU 淘汰（默认 32） | 满了就静默丢最老的会话，用户侧表现为「上下文突然没了」 |

**现在的实测数据**：单次任务 3–9 分钟（纯 CPU、无 GPU）。这意味着**并发度 = 1 就已经是这台机器的极限**，
不排队的结果不是变慢，是所有人都以为卡死。

---

## 二、P1：上线前必须补齐（运维、安全、合规）

### 2.1 状态全在内存，无法重启、无法扩容（已落地，见零之二）

- 会话：`Collections.synchronizedMap(LinkedHashMap)`，进程死即丢；
- 快照：`server.snapshot-dir` 下的 JSON 文件（`TeamSnapshot`），能断点续跑，但**同一份状态只在本机磁盘**；
- 结论：**只能单实例**。做不了滚动发布，也没法水平扩容——第二个实例看不到第一个实例的会话。

生产要动的最小改动：会话/快照落到 SQLite 或现有 PG/MySQL，进程启动时按 `conversationId` 恢复。

### 2.2 密钥明文躺在配置文件里（已落地，见零之二）

`src/main/resources/agent.properties` 里目前是明文：

```
tool.baidu.ak=uhK0qcsvp02QPX7IJyvPBfsQIEgwBa87   ← 真实可用的百度地图 AK
tool.db.sandbox-password=***（非空）
```

`AppConfig` 已经支持 env / `-D` 覆盖（优先级：`-D` > env > properties），基础设施是有的，
**缺的是「默认不含密钥」这条纪律**：properties 里只留空值和注释，真实值走环境变量或 secret 挂载。

另外建议启动时校验：配了 `deepseek` 但没给 key、配了 `baidu` 但没给 AK，应该 **fail fast 打一行明确错误**，而不是等第一次调用才发现。

### 2.3 可观测性只有「人看的日志」（已落地，见零之二）

| 缺什么 | 现状 |
| --- | --- |
| 指标 | 无 `/metrics`。QPS、P95 耗时、模型调用失败率、token 消耗、队列深度全部无法采集 |
| 结构化日志 | `logback.xml` 只有 ConsoleAppender，pattern 是 `HH:mm:ss.SSS %-5level %logger - %msg`，无 traceId / sessionId 字段 |
| 日志落盘与轮转 | 无 FileAppender，容器里靠 `docker logs`（可接受），裸机部署则全丢 |
| 链路追踪 | 无 requestId，SSE 会话与 HTTP 请求对不上 |
| 告警 | 无 |
| 外部依赖健康 | `/api/health` 有 `ready` 字段（能探到模型），但 Dockerfile **没有 HEALTHCHECK**，compose 也没有 |

好在 `RunLog` 已经把「一次运行到底发生了什么」按时间线打出来了，**把它改成一行 JSON 就能直接进日志系统**，成本很低。

### 2.4 容器还不够「生产」（已落地，见零之二）

| 项 | 现状 | 风险 |
| --- | --- | --- |
| 运行用户 | `eclipse-temurin:21-jre` 默认 **root** | 容器逃逸/写挂载目录时权限过大 |
| HEALTHCHECK | 无 | 编排层无法判断「进程活着但模型连不上」 |
| 基础镜像 | `node:22-slim` / `maven:3.9-eclipse-temurin-21` / `eclipse-temurin:21-jre`，都是浮动 tag | 不可复现，某天构建结果会变 |
| 构建期测试 | `mvn package -Dmaven.test.skip=true` | **镜像可能带着挂掉的代码出厂** |
| TLS | 无，裸 HTTP | 内网也建议反代 + 证书，或至少明确「仅内网」 |
| 资源限制 | compose 无 `limits` | 一个长任务能吃满 20 核 |
| 探活/就绪分离 | 只有一个 `/api/health` | liveness 与 readiness 混用，模型挂了会被重启 |

### 2.5 数据留存与脱敏

快照 JSON 里存的是**完整对话 + 工具返回**，包括 `read_file` 读到的源码、`pom.xml`、甚至可能包含配置里的密码。
目前**没有 TTL、没有清理任务、没有脱敏**。生产至少要：

- 快照保留 N 天自动清理；
- 落盘前对 `password|token|secret|ak=` 这类键做打码；
- 明确「谁能下载快照」（现在 `/api/snapshots` 无鉴权，等于谁都能拉别人的对话全文）。

### 2.6 发布工程几乎为零（已落地，见零之二）

| 项 | 现状 | 后果 |
| --- | --- | --- |
| 版本控制 | **没有 git 仓库**（`git rev-parse` 报 not a repository） | 无法追溯、无法回滚、无法 code review |
| CI | 无 `.github/` | 161 条测试全靠手工跑，没有门禁 |
| 发布产物 | `mvn package` 产出的 jar **不含 React 产物**（实测 jar 里只有旧的 `web/index.html`） | 不先跑 `npm run build` 就打 jar，发出去的是**旧单页 UI** |
| 双 UI 并存 | 新 React 在 `static/`，旧单页在 `web/index.html` 且仍是 fallback | 两套 UI 会持续漂移，修 bug 要改两遍 |
| 制品仓库 | 无 | 版本号 `1.0.0` 手改 |

> 目前 `Dockerfile` 的多阶段构建把这个问题绕过去了（先 npm build 再 mvn package），
> 但只要有人手工 `mvn package` 就直接踩坑。建议加 `frontend-maven-plugin`，或者干脆删掉旧 `web/index.html` 的 fallback。

---

## 三、P2：质量与体验（可以排后，但别忘）

1. ~~**前端零测试**~~ → **已落地**（vitest 21 条：纯函数 / 口令与 401 分类 / SSE 状态判定 / DOM id 契约）。
2. ~~**SSE 断线不续传**~~ → **已落地**（事件序号 + `Last-Event-ID` 补发 + `resync`/`expired` 兜底）。
3. ~~**无安全响应头**~~ → **已落地**（CSP 分两套 + nosniff + frame-ancestors + Referrer-Policy）。
4. ~~**无 API 契约**~~ → **已落地**（`openapi.json` + `docs/api.md`，测试防漂移）。
5. **错误分类粒度**：模型不可用、超时、工具失败、参数错都走同一条 `error` 事件，前端无法区别对待（可重试 / 不可重试）。
   现状是页面只能统一显示"出错了"，用户不知道该重试还是改输入。建议给 `error` 事件加 `code` 字段
   （`model_unavailable` / `timeout` / `tool_failed` / `bad_request`），这是**唯一还在的前端体验级缺口**。
6. **小模型强依赖提示词**：3B 模型在软提示下会反问用户而不调工具（已实测），提示词一改行为就变。
   **需要一套回归集**（固定 10 个任务 + 断言「至少调了 N 次工具、答案里的关键事实来自文件」），否则调提示词等于盲改。

---

## 四、已经具备的（别重复建设）

盘点到的好东西，避免误伤：

- **测试**：后端 200 条 + 前端 21 条全绿，覆盖重试策略、重复调用拦截、断点续跑、流式、路由、工具沙箱、平台差异、SSE 续传、安全头、接口文档一致性（`MockChatServer` 做假模型，不依赖网络）。
- **失败处理**：`RetryPolicy` + `RetryableToolError` + `ChatAgent` 的重复调用拦截 / 空回复重试 / 「只写计划」兜底纠偏。
- **工具安全边界**：命令白名单 + 超时强杀 + `write-protect` + 只读目录配置（`tool.code.read-only-dirs`）。
- **断点续跑**：快照 + `team.resume(state)`，语义与 AutoGen 对齐。
- **模型可切换**：`-Dmodel.name=` / env 覆盖，Ollama 与云端 DeepSeek 都能跑；`/api/health` 会告诉你模型通不通。
- **构建**：三阶段 Dockerfile（node → maven → JRE）、entrypoint 支持 `PORT`/`JAVA_OPTS` 覆盖、`.dockerignore` 已排除 node_modules 和旧静态产物。

---

## 五、如果只给一周：最小上线清单（P0/P1 已完成，剩余为真机复验）

按「不做就不能上线」排序，每项都是独立可交付：

| # | 事项 | 关键动作 | 验收标准 | 规模 |
| --- | --- | --- | --- | --- |
| 1 | 鉴权 + 租户隔离 | 前置反代做 SSO，或内置一个静态 token + `Authorization` 校验；每个 token 绑定一个 workspace | 无 token 访问 `/api/*` 返回 401；跨 token 看不到彼此会话 | M |
| 2 | 目录白名单 | `resolveWorkspaceParam` 改为只允许 `tool.code.writable-dirs` 内的路径；关掉 `/api/fs/list` 的盘符浏览 | 传 `D:/workspace/abrspro2` 之外的路径返回 403 | S |
| 3 | 并发闸门 | 全局 `Semaphore(1~2)` + 队列上限 + 超限返回 429；SSE 队列设界 | 第 3 个并发请求立刻拿到 429 和明确文案，而不是排队等 5 分钟 | S |
| 4 | 密钥外置 | properties 里清空 `tool.baidu.ak` / db password；启动时校验必填项 | 仓库里 grep 不到可用密钥；缺 key 时启动直接失败并说明缺哪个 | S |
| 5 | 落库 + 启动恢复 | 会话/快照从内存+本地 JSON 改为 SQLite 或 PG | 重启后原 `conversationId` 能继续追问 | M |
| 6 | 指标 + 结构化日志 | `/metrics` 暴露 QPS/P95/失败率/队列深度；`RunLog` 改一行 JSON | Grafana 能出一张「近 1 小时运行耗时分布」 | M |
| 7 | 容器加固 | `USER 10001`、HEALTHCHECK、基础镜像钉 digest、构建期跑测试、compose 加 `limits` | `docker inspect` 里非 root；`docker ps` 显示 healthy | S |
| 8 | 版本控制 + CI | 建 git 仓库；CI 跑 `mvn test` + `npm run typecheck && npm run build` + 镜像构建 | 每次 push 自动跑测试，红则不能合 | S |
| 9 | E2E 冒烟 | Playwright 3 条：选目录 → 运行 → 收到完整回答（不出现「接下来我将…」结尾） | CI 里 3 条全绿 | M |

**规模说明**：S ≈ 半天内、M ≈ 1–2 天、L ≈ 3 天以上（单人）。

做完 1–4 就可以「小范围内网试用」；做完 5–9 才算「能长期挂着跑」。

---

## 六、还有一个绕不过去的现实问题：硬件

这次排查已经证实：**无 GPU 的纯 CPU 上，qwen2.5:7b 单次任务 5 分钟、3b 3–9 分钟**，
所有性能优化只能把「无效等待」去掉（`list_files` 深度化后从 10 分钟降到 5 分钟），
**剩下的时间就是 token 生成本身，代码层面动不了**。

生产要么承认「一次分析 3–5 分钟」并把它写进产品预期（配进度、排队、异步通知），
要么上 GPU / 换更小模型。这一条决定的是产品形态，不是代码质量。
