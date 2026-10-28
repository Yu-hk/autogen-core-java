"""跑一次「写代码」场景，把事件流打出来，最后列出工作目录里落盘的文件。

用法：
    python tools/run-coding.py                      # 用场景自带的示例任务
    python tools/run-coding.py "任务描述"
    python tools/run-coding.py "任务描述" auto        # scenario=auto：顺便验证自动路由

scenario 默认 coding；传 auto 时由 Router 判断，正好验证"用户只提问"这条路径。

注意：流式模式下服务端只推 delta（正文片段），assistant 的最终文本要用 delta 拼。
"""
import json
import os
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

task = sys.argv[1] if len(sys.argv) > 1 else (
    "用 Java 写一个线程安全的 LRU 缓存：泛型、固定容量、get/put，容量满时淘汰最久未使用的键。"
    "再写一个 main 自测（命中、淘汰、并发各覆盖一次），编译并运行，直到输出 PASS。"
)
scenario = sys.argv[2] if len(sys.argv) > 2 else "coding"
workspace = Path(os.environ.get("CODE_WORKSPACE", "target/code-workspace"))

params = {"scenario": scenario, "task": task, "maxRounds": "1"}
# 绝对路径 = 页面上"选工作目录"的等价操作：让服务端把这次 run 的工作目录换成它
if workspace.is_absolute():
    params["workspace"] = str(workspace).replace("\\", "/")
started = json.load(urllib.request.urlopen(
    "http://127.0.0.1:8097/api/run?" + urllib.parse.urlencode(params)))
sid = started["sessionId"]
print(f"session {sid} scenario={scenario} workspace={started.get('workspace')}", flush=True)
print(f"任务：{task}\n", flush=True)

t0 = time.time()
buf, order, tools, outcome = {}, [], [], {}
with urllib.request.urlopen(f"http://127.0.0.1:8097/api/stream?sessionId={sid}", timeout=1800) as resp:
    for raw in resp:
        line = raw.decode("utf-8").strip()
        if not line.startswith("data: "):
            continue
        ev = json.loads(line[6:])
        kind = ev.get("kind")
        stamp = f"[{time.time() - t0:6.1f}s]"

        if kind == "route":
            print(f"{stamp} 路由 → {ev.get('scenario')}（{ev.get('title')}）· {ev.get('reason')}", flush=True)
        elif kind == "delta":
            agent = ev.get("agent")
            if agent not in buf:
                order.append(agent)
            buf[agent] = buf.get(agent, "") + (ev.get("text") or "")
        elif kind == "tool":
            phase = ev.get("phase")
            name = ev.get("tool")
            if phase == "request":
                args = str(ev.get("args") or ev.get("arguments") or "")
                tools.append(name)
                print(f"{stamp} → {name} {args[:180]}", flush=True)
            else:
                result = str(ev.get("result") or "")
                print(f"{stamp} ← {name} ({ev.get('millis')}ms) {result[:400]}", flush=True)
        elif kind == "event" and ev.get("name") == "SpeakingFinished":
            print(f"{stamp} {ev.get('agent')} 发言结束 · {ev.get('length')} 字", flush=True)
        elif kind in ("done", "error"):
            outcome = ev
            print(f"{stamp} {kind}: {json.dumps(ev, ensure_ascii=False)[:400]}", flush=True)
            break

print(f"\n=== 最终回答（总耗时 {time.time() - t0:.1f}s，工具调用 {len(tools)} 次）===")
for agent in order:
    text = buf.get(agent, "")
    print(f"\n--- {agent}（{len(text)} 字）---\n{text or '(无)'}")

print(f"\n=== 工具调用序列 ===")
print(" → ".join(tools) if tools else "(没有调用任何工具)")

print(f"\n=== 工作目录 {workspace} ===")
if os.environ.get("CODE_SKIP_LIST"):
    # 指向真实工程时别 rglob：几万个文件，跑一遍比整个任务还慢
    print("(已跳过文件清单：CODE_SKIP_LIST 已设置)")
elif not workspace.exists():
    print("(目录不存在——模型什么都没写)")
else:
    for path in sorted(workspace.rglob("*")):
        if path.is_file():
            print(f"{path.relative_to(workspace)}  {path.stat().st_size} 字节")
