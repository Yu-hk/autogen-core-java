"""
批量验证自动路由：同一批问题分别打给 /api/run（不带 scenario），看后端判成哪条路。

用法：
    python tools/route-check.py              # 内置样例
    python tools/route-check.py "问题1" "问题2"

只读 route 事件与 done 事件，不打印完整回答——这个脚本是给"路由判断"用的，
回答质量请看页面或 tools/run-travel.py。
"""

import json
import sys
import time
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8097"

SAMPLES = [
    "橘子洲的经纬度是多少",                                  # 单诉求旅游 → travel
    "帮我做一个订单导出功能，要求支持百万级数据",                 # 技术需求 → review
    "设计一个百万级订单的导出方案",                            # 技术初稿 → refine
    "怎么给 Git 提交改作者信息",                              # 日常提问 → general
]


def drain(session_id):
    """读 SSE 直到服务端关闭连接。"""
    events = []
    with urllib.request.urlopen(f"{BASE}/api/stream?sessionId={session_id}", timeout=900) as resp:
        for raw in resp:
            line = raw.decode("utf-8").strip()
            if line.startswith("data: "):
                events.append(json.loads(line[6:]))
    return events


def run(task):
    started = time.time()
    query = urllib.parse.urlencode({"scenario": "auto", "task": task})
    req = urllib.request.Request(f"{BASE}/api/run?{query}", method="POST")
    with urllib.request.urlopen(req, timeout=30) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    if body.get("error"):
        return {"error": body["error"], "seconds": time.time() - started}

    events = drain(body["sessionId"])
    route = next((e for e in events if e.get("kind") == "route"), {})
    done = next((e for e in events if e.get("kind") == "done"), {})
    error = next((e for e in events if e.get("kind") == "error"), {})
    return {
        "scenario": route.get("scenario", "?"),
        "title": route.get("title", "?"),
        "reason": route.get("reason", "?"),
        "seconds": round(time.time() - started, 1),
        "tools": done.get("toolCalls", 0),
        "rounds": done.get("round", 0),
        "stop": done.get("stopReason", error.get("message", "?")),
    }


def main():
    tasks = sys.argv[1:] or SAMPLES
    for task in tasks:
        result = run(task)
        print("-" * 78)
        print("问：" + task)
        if "error" in result:
            print("  错误：" + result["error"])
            continue
        print(f"  路由：{result['scenario']}（{result['title']}）")
        print(f"  依据：{result['reason']}")
        print(f"  耗时：{result['seconds']}s · 轮次 {result['rounds']} · 工具 {result['tools']} 次")
        print(f"  停止：{result['stop']}")


if __name__ == "__main__":
    main()
