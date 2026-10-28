# 接口速查

机器可读的描述在 **`GET /api/openapi.json`**（OpenAPI 3.1），可直接导入 Postman / Apifox / Insomnia。
这份文档讲的是描述文件里放不下的东西：**为什么这样设计、出错时先看哪里**。

## 一、口令

除静态页面外，所有 `/api/` 路径都要口令（`server.auth-token`，或用环境变量 `SERVER_AUTH_TOKEN`）。三种带法：

```bash
# 1. 请求头（脚本、curl 用这个）
curl -H "Authorization: Bearer $SERVER_AUTH_TOKEN" http://127.0.0.1:8097/api/health

# 2. 换成自定头
curl -H "X-Auth-Token: $SERVER_AUTH_TOKEN" http://127.0.0.1:8097/api/health

# 3. 查询参数（只给 EventSource 用——浏览器的 SSE 接口加不了请求头）
curl "http://127.0.0.1:8097/api/health?token=$SERVER_AUTH_TOKEN"
```

没带或带错一律 **401**，响应体是 `{"error":"需要访问口令：…"}`。带 `?token=` 时注意：
URL 可能进代理访问日志，`Referrer-Policy: no-referrer` 只能保证它不被带去外链。

> 绑定非回环地址（`server.host=0.0.0.0`）却没配口令时，服务**拒绝启动**。
> 要在内网裸跑，得显式写 `server.auth-allow-anonymous=true`——这是为了让人"故意"做这个决定。

## 二、接口一览

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/health` | 服务与模型状态；`ready=false` 时发起运行会立刻收到 `error` 事件 |
| GET | `/api/metrics` | Prometheus 文本指标 |
| GET | `/api/scenarios` | 可选的协作方式与成员 |
| GET | `/api/fs/list?path=` | 浏览可选工作目录（只列目录） |
| POST | `/api/run` | 发起一次运行，立刻返回 `sessionId` |
| GET | `/api/stream?sessionId=` | 事件流（SSE） |
| GET | `/api/snapshots` | 历史快照列表 |
| GET | `/api/conversations[?conversationId=]` | 会话落库：不带 id 列摘要，带 id 取整段 |
| POST | `/api/resume?file=` | 从快照续跑 |
| POST | `/api/stop?sessionId=` | 请求停止（当前轮结束后收尾） |
| GET | `/api/openapi.json` | 描述文件本身 |

`/api/run` 的参数：

| 参数 | 说明 |
| --- | --- |
| `task` | 用户的问题；`scenario=auto` 时必填（否则 400） |
| `scenario` | 协作方式 id，默认 `auto`（交给路由判断） |
| `maxRounds` | 最大轮次；auto 路径下由路由结果决定，这里的值不生效 |
| `workspace` | 工作目录，必须在 `tool.code.writable-dirs` 白名单内 |
| `conversationId` | 带上就是追问；磁盘/内存都找不到时当新对话 |

## 三、事件流与断点续传

响应是 `text/event-stream`，每条事件由 `id:` 和 `data:` 两行组成：

```
retry: 3000

id: 5
data: {"kind":"delta","agent":"writer","round":1,"text":"…","seq":5}

id: 6
data: {"kind":"done","stopReason":"…","round":1,"seq":6}
```

`kind` 有 12 种：`status` / `route` / `history` / `message` / `delta` / `event` / `tool` / `retry` /
`done` / `error` / `resync` / `expired`。前 10 种是正常流程，后 2 种只在异常路径上出现：

| 事件 | 什么时候来 | 页面该做什么 |
| --- | --- | --- |
| `resync` | 断线太久，断开期间的事件已被保留窗口（6000 条）挤出 | 重新拉一次 `/api/conversations?id=` 重建对话区，然后继续跟流 |
| `expired` | 会话已回收（服务重启过，或这轮结束太久） | 提示重新发起，并**主动关闭连接**（否则浏览器会重连→又拿到 expired→死循环） |

**断点续传是浏览器自动完成的**：服务端给每条事件发了 `id:`，浏览器断线重连时会自动带上
`Last-Event-ID` 请求头，服务端从那条之后接着发。前端不需要写重连逻辑，也**不应该**在
`onerror` 里直接 `close()`——那会把这条免费的能力扔掉，一次网络抖动就变成"连接断开"。

命令行复现（排查续传是否正常时很有用）：

```bash
# 第一次：从头读几条就断开
curl -N --noproxy 127.0.0.1 -H "Authorization: Bearer $T" \
  "http://127.0.0.1:8097/api/stream?sessionId=abc12345" | head -6

# 第二次：带游标，应当从刚才的下一条开始，既不重放也不跳号
curl -N --noproxy 127.0.0.1 -H "Authorization: Bearer $T" -H "Last-Event-ID: 3" \
  "http://127.0.0.1:8097/api/stream?sessionId=abc12345"
```

也可以用 `?lastEventId=3` 代替请求头，两者等价（服务端优先看请求头）。

事件在服务端保留最近 **6000 条**。这个数字的取法：一次长回答的流式增量在几千条量级，
6000 能覆盖"整轮没连上、跑完再补"这个最坏情况，单会话约 1–2 MB，会话本身还会被淘汰清理。

## 四、错误码

| 状态码 | 含义 | 常见原因 |
| --- | --- | --- |
| 400 | 参数不合法 | 没问题就发了 `scenario=auto`；工作目录不在白名单 |
| 401 | 没口令 / 口令不对 | 见第一节 |
| 404 | 目标不存在 | 快照文件没了；`/api/stop` 传的是 `conversationId` 而不是 `sessionId` |
| 405 | — | 目前没有方法校验，别指望它 |
| 429 | 并发已满 | `web.max-concurrent-runs`（默认 2）被占满，响应体带 `busy` 与 `limit` |
| 500 | 服务端异常 | 响应体里有异常类名，配合 `X-Request-Id` 去 `access-*.jsonl` 里查 |

> `404` 有两种来源，排查时看响应体：`{"error":"not found: api/xxx"}` 是**路由不存在**（路径拼错），
> 其余 404 都是业务层说"这个东西没有"。

## 五、怎么用 X-Request-Id 追一次请求

每个响应都带 `X-Request-Id`。拿它去日志里捞：

```bash
curl -s -D - -o /dev/null -H "Authorization: Bearer $T" \
  http://127.0.0.1:8097/api/health | grep -i x-request-id

jq -c 'select(.requestId=="1a2b3c4d")' target/logs/*.jsonl
```

同一次运行产生的 `run-*.jsonl` 记录也带同一个 `requestId`，所以一条命令能把
"浏览器点了一下 → HTTP 请求 → 模型调用 → 工具调用 → 收尾"串成一条线。
细节见 [observability.md](observability.md)。
