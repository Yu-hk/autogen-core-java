"""
多轮对话验证：同一 conversationId 连问几句，看第二轮是否真的带着上文。

用法：
    python tools/multi-turn-check.py
    python tools/multi-turn-check.py "第一句" "第二句" "第三句"

这条脚本存在的理由：多轮最容易出现的"假成功"是——页面看着像接着聊，
实际每次都是新会话，模型凭常识把话圆回来了。所以这里除了打印回答，
还会把每一轮的路由依据和消息条数打出来：消息数一轮轮往上加，才说明上文真的接上了。
"""

import json
import sys
import time
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8097"

# 第二、三句刻意不带域词：单看它们会被判成 general，
# 能沿用上一轮才说明"接着上文"这条路是通的。
SAMPLES = [
    "橘子洲的经纬度是多少",
    "那第二个呢",
    "附近有什么好吃的",
]


def drain(session_id):
    events = []
    with urllib.request.urlopen(f"{BASE}/api/stream?sessionId={session_id}", timeout=900) as resp:
        for raw in resp:
            line = raw.decode("utf-8").strip()
            if line.startswith("data: "):
                events.append(json.loads(line[6:]))
    return events


def run(task, conversation_id):
    started = time.time()
    params = {"scenario": "auto", "task": task}
    if conversation_id:
        params["conversationId"] = conversation_id
    query = urllib.parse.urlencode(params)
    req = urllib.request.Request(f"{BASE}/api/run?{query}", method="POST")
    with urllib.request.urlopen(req, timeout=30) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    if body.get("error"):
        return {"error": body["error"], "seconds": round(time.time() - started, 1)}

    events = drain(body["sessionId"])
    route = next((e for e in events if e.get("kind") == "route"), {})
    done = next((e for e in events if e.get("kind") == "done"), {})
    error = next((e for e in events if e.get("kind") == "error"), {})
    history = next((e for e in events if e.get("kind") == "history"), {})
    retries = [e for e in events if e.get("kind") == "retry"]
    texts = [e.get("text", "") for e in events if e.get("kind") == "message"
             and e.get("source") != "user"]
    # 流式场景里最终回答是由 delta 一片片推出来的，不会有完整 message 事件；
    # 只有续跑回放历史时才有 message。两种都收，取能拿到的那份。
    deltas = "".join(e.get("text", "") for e in events if e.get("kind") == "delta")

    return {
        "conversation": body.get("conversationId", "?"),
        "followUp": body.get("followUp", False),
        "scenario": route.get("scenario", "?"),
        "title": route.get("title", "?"),
        "reason": route.get("reason", "?"),
        "seconds": round(time.time() - started, 1),
        "messages": done.get("messages", history.get("count", 0)),
        "historyCount": history.get("count", 0),
        "rounds": done.get("round", 0),
        "tools": done.get("toolCalls", 0),
        "retries": len(retries),
        "answer": (texts[-1] if texts else deltas).strip(),
        "stop": done.get("stopReason", error.get("message", "?")),
    }


def main():
    tasks = sys.argv[1:] or SAMPLES
    conversation = None
    for index, task in enumerate(tasks, start=1):
        result = run(task, conversation)
        print("=" * 78)
        print(f"第 {index} 问：{task}")
        if "error" in result:
            print("  错误：" + result["error"])
            return
        conversation = result["conversation"]
        print(f"  会话：{conversation} · {'追问（带上了上文）' if result['followUp'] else '新对话'}")
        print(f"  路由：{result['scenario']}（{result['title']}）")
        print(f"  依据：{result['reason']}")
        print(f"  耗时：{result['seconds']}s · 轮次 {result['rounds']} · "
              f"工具 {result['tools']} 次 · 重试 {result['retries']} 次")
        print(f"  上下文：{result['messages']} 条消息（本轮开始时已有 {result['historyCount']} 条）")
        print("  回答：")
        for line in (result["answer"] or "（无）").splitlines():
            print("    " + line)
    print("=" * 78)
    print("看一眼最后两轮的消息条数：逐轮递增说明上文真的接上了；始终是 2，说明每轮都在重开。")


if __name__ == "__main__":
    main()
