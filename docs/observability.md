# 可观测性：指标、traceId、结构化日志

> 目标只有一个：**出问题时能在五分钟内说清"哪个请求、卡在哪一步、为什么失败"**。
> 之前这三问全答不上来——页面只给结果，服务端只有一行行"人看的字符串"。

## 一、三个入口

| 入口 | 位置 | 给谁用 |
| --- | --- | --- |
| 指标 | `GET /api/metrics`（需口令） | Prometheus / Grafana，看趋势与告警 |
| traceId | 每个响应的 `X-Request-Id` 头 | 用户反馈问题时提供，或从浏览器 devtools 抄 |
| 结构化日志 | `<web.log-dir>/access-YYYY-MM-DD.jsonl`、`run-YYYY-MM-DD.jsonl` | 事后按 traceId 追一次请求 |

默认 `web.log-dir=target/logs`，容器里是 `/data/logs`。关掉：`web.structured-log=false`。

## 二、traceId 怎么流动

```
浏览器点「运行」
  └─ POST /api/run          响应头 X-Request-Id: 1a2b3c4d   ← RequestLog.begin()
       ├─ access-*.jsonl    {"requestId":"1a2b3c4d","path":"/api/run","status":200,"millis":12}
       └─ 后台线程
            ├─ run-*.jsonl  {"event":"start","run":"9f3a11b0","requestId":"1a2b3c4d",...}
            ├─ run-*.jsonl  {"event":"note","requestId":"1a2b3c4d","message":"路由 → coding：..."}
            └─ run-*.jsonl  {"event":"done","requestId":"1a2b3c4d","round":4,"stopReason":"命中终止词"}
```

`requestId` 在 `route()` 里生成并塞进线程上下文，`RunLog` 在**提交任务前**取一次
（lambda 会跑到线程池的另一个线程，那时上下文已经没了）。所以一次点击 = 一个 requestId，
它下面挂着服务端所有相关记录。

排查示例：

```bash
# 某个 traceId 的全部记录（访问日志 + 运行日志一起看）
jq -c 'select(.requestId=="1a2b3c4d")' /data/logs/*.jsonl

# 今天失败最多的接口状态码
jq -r 'select(.status >= 400) | .path + " " + (.status|tostring)' /data/logs/access-*.jsonl | sort | uniq -c

# 最慢的 10 次运行
jq -c 'select(.event=="done")' /data/logs/run-*.jsonl | jq -s 'sort_by(-.millis)[:10]'
```

> SSE 连接在结束时记 `"status":0` —— 它是长连接，没有单一状态码，0 表示"流式写完"。
> 看到 `/api/stream` 且 status=0 是正常的，不是失败。

## 三、指标清单

| 指标 | 类型 | 含义 |
| --- | --- | --- |
| `agentcore_uptime_seconds` | gauge | 进程运行时长 |
| `agentcore_active_runs` | gauge | 正在跑的任务数（= 上限 − 剩余名额） |
| `agentcore_max_concurrent_runs` | gauge | 并发上限（`web.max-concurrent-runs`） |
| `agentcore_conversations` | gauge | 内存中缓存的会话数 |
| `agentcore_runs_started_total` | counter | 累计启动的运行 |
| `agentcore_runs_finished_total{reason}` | counter | 按停止原因归类：`max_rounds` / `stop_word` / `timeout` / `external` / `error` / `unknown` / `other` |
| `agentcore_run_failures_total` | counter | 异常结束（含模型不可用） |
| `agentcore_run_duration_seconds` | histogram | 运行耗时，桶 5s→1800s |
| `agentcore_tool_calls_total` | counter | 工具调用次数 |
| `agentcore_model_retries_total` | counter | 模型重试次数 |
| `agentcore_sse_streams_total` | counter | SSE 连接数 |
| `agentcore_sse_resumes_total` | counter | 带游标重连（断点续传）次数。两个 SSE 指标的差值就是"断过几次"，内网链路质量看这个 |
| `agentcore_http_requests_total{status}` | counter | 按状态码 |
| `jvm_memory_used_bytes` / `_committed_` / `_max_` | gauge | 堆内存 |
| `jvm_nonheap_used_bytes` / `jvm_threads_live` | gauge | 元空间、线程数 |

**停止原因是归一化过的**：原始文案是自由文本（"达到最大轮次 4"），直接当 label 会让时序数量
随每次运行增长，Prometheus 会被打爆。原始文案仍在 `run-*.jsonl` 的 `stopReason` 字段里。

**SSE 续传的两个数怎么用**：`sse_streams_total - sse_resumes_total` 是全新的连接数，
两者的比值反映断线率。内网正常情况下一场运行只该有 1 条连接；如果比值长期偏高，
通常是中间有代理掐了长连接（`proxy_read_timeout` 默认 60s，而我们的流可以跑几分钟），
调代理比改代码更有效。

## 四、建议的告警（先三条就够）

| 条件 | 为什么 | 阈值起点 |
| --- | --- | --- |
| `agentcore_active_runs == limit` 持续 10 分钟 | 说明一直有人被 429，机器不够或任务卡住 | 10min |
| `increase(agentcore_run_failures_total[15m]) > 3` | 连续失败通常是模型端点挂了/Key 失效 | 3 次 |
| `jvm_memory_used_bytes / jvm_memory_max_bytes > 0.9` 持续 5 分钟 | 纯 CPU 推理 + 长上下文容易堆爆 | 0.9 |

## 五、Prometheus 接入

```yaml
scrape_configs:
  - job_name: agentcore
    metrics_path: /api/metrics
    authorization:
      type: Bearer
      credentials: <SERVER_AUTH_TOKEN>
    static_configs:
      - targets: ["agentcore:8097"]
```

指标走 `/api/` 前缀意味着**也要口令**——指标里有业务量与失败率，不该对全网开放。

## 六、这套东西刻意没做的

- **没引 Micrometer/OpenTelemetry**：十几个计数器自己写三十行就够，引框架要为它配 registry、埋点、导出器。
  真接入分布式追踪时再换，`Metrics` 与 `RequestLog` 是隔离的，不影响别的代码。
- **没做日志轮转**：按天切分，TTL 靠外部（容器卷 + 定时清理）。一天几十 MB 的量，还不值得写轮转逻辑。
- **没做采样**：请求量级是"每人每天几十次"，全量记录比采样更好排查。
