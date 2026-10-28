#!/bin/sh
# 容器入口：支持用环境变量覆盖 JVM 参数与端口。
# 端口通过 "web <port>" 传给 Main，覆盖 agent.properties 的 server.port；
# 绑定地址由 SERVER_HOST 环境变量决定（Dockerfile 已默认 0.0.0.0）。
set -e

PORT="${PORT:-${SERVER_PORT:-8097}}"

# 默认 JVM 参数：容器里最容易踩的是"堆按宿主机内存开"——
# 容器限 2G 而 JVM 看到 64G，堆直接开很大，然后被 OOMKill。
# MaxRAMPercentage 让 JVM 认 cgroup 限制；ExitOnOutOfMemoryError 保证
# 真 OOM 时进程退出让编排重启，而不是留一个半死不活的进程继续接请求。
JAVA_OPTS="${JAVA_OPTS:--XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError}"

# 数据目录只读挂载时给出明确一句话，而不是让第一次落盘时才报错
for dir in "${WEB_DATA_DIR:-/data}" "${WEB_LOG_DIR:-/data/logs}"; do
    if [ ! -w "$dir" ]; then
        echo "[启动] 警告：$dir 不可写。会话与日志将无法持久化（检查卷的属主是否为 uid 10001）。" >&2
    fi
done

exec java $JAVA_OPTS -jar /app/app.jar web "$PORT"
