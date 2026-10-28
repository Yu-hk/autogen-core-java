"""跑一次旅游场景，把事件流打印到 stdout。

用法：python tools/run-travel.py "任务描述" [maxRounds] [scenario]

scenario 默认 travel（单 agent）；分工流水线传 travel-pipeline。

注意：流式模式下服务端只推 delta（正文片段），不推 assistant 的 message 事件
（message 事件仅用于 user 回显），所以每个角色的最终文本要靠累积 delta 拼出来。
"""
import json
import sys
import time
import urllib.parse
import urllib.request

task = sys.argv[1] if len(sys.argv) > 1 else "查一下长沙岳麓山的经纬度，然后告诉我它大概的位置。"
rounds = sys.argv[2] if len(sys.argv) > 2 else "2"
scenario = sys.argv[3] if len(sys.argv) > 3 else "travel"

params = {"scenario": scenario, "task": task, "maxRounds": rounds}
sid = json.load(urllib.request.urlopen("http://127.0.0.1:8097/api/run?" + urllib.parse.urlencode(params)))["sessionId"]
print("session", sid, "scenario", scenario, flush=True)

start = time.time()
buf = {}
order = []
with urllib.request.urlopen(f"http://127.0.0.1:8097/api/stream?sessionId={sid}", timeout=1800) as resp:
    for raw in resp:
        line = raw.decode("utf-8").strip()
        if not line.startswith("data: "):
            continue
        ev = json.loads(line[6:])
        kind = ev.get("kind")
        if kind == "delta":
            agent = ev.get("agent")
            if agent not in buf:
                order.append(agent)
            buf[agent] = buf.get(agent, "") + (ev.get("text") or "")
        elif kind == "tool":
            print(f"[{time.time() - start:6.1f}s] {ev.get('phase'):8} {ev.get('tool')} -> {str(ev.get('result', ''))[:100]}", flush=True)
        elif kind == "message":
            print(f"[{time.time() - start:6.1f}s] 消息 {ev.get('source')}: {str(ev.get('text'))[:150]}", flush=True)
        elif kind == "event" and ev.get("name") == "SpeakingFinished":
            print(f"[{time.time() - start:6.1f}s] {ev.get('agent')} 发言结束 · {ev.get('length')} 字", flush=True)
        elif kind in ("done", "error"):
            print(f"[{time.time() - start:6.1f}s] {kind}: {json.dumps(ev, ensure_ascii=False)[:300]}", flush=True)
            break

print(f"\n=== 各角色输出（总耗时 {time.time() - start:.1f}s）===")
for agent in order:
    text = buf.get(agent, "")
    print(f"\n--- {agent}（{len(text)} 字）---")
    print(text or "(无)")
