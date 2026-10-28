# syntax=docker/dockerfile:1

# ============================================================
# 构建约定
#   · 三个基础镜像都写死到 patch 级 tag：浮动 tag（如 node:22-slim）会在某天
#     悄悄换成另一个版本，构建结果跟着变，出问题时无法复现。
#   · 测试在镜像构建期就跑（--build-arg SKIP_TESTS=1 可跳过，只给紧急发布用）。
#   · 运行镜像用非 root 用户，只带 JRE + jar + 依赖。
# ============================================================

# ---------- 阶段 1：前端 ----------
FROM node:22.11.0-slim AS frontend
WORKDIR /source
# 只拷前端工程，便于利用层缓存（package-lock.json 已锁定版本）
COPY frontend/ ./frontend/
RUN cd frontend \
    && npm ci \
    && npm run build \
    && npm cache clean --force

# ---------- 阶段 2：后端测试 ----------
# 单独一个阶段，是为了让"测试挂了"和"打包挂了"在构建日志里一眼能分开。
FROM maven:3.9.9-eclipse-temurin-21 AS test
WORKDIR /source
COPY pom.xml ./pom.xml
COPY src ./src
# 先把依赖拉下来单独成层：改代码不会导致重新下载整个依赖树
RUN mvn -B -q dependency:go-offline
ARG SKIP_TESTS=false
RUN if [ "$SKIP_TESTS" = "true" ]; then \
        echo "已跳过测试（SKIP_TESTS=true）"; \
    else \
        mvn -B test; \
    fi

# ---------- 阶段 3：打包 ----------
FROM test AS backend
# 前端产物落在 src/main/resources/static，随 Maven 一起进 jar
COPY --from=frontend /source/src/main/resources/static ./src/main/resources/static
# 测试已在上一个阶段跑完，这里只打包，避免重复跑一遍
RUN mvn -B -q package -DskipTests

# ---------- 阶段 4：运行 ----------
FROM eclipse-temurin:21.0.5_11-jre AS runtime

LABEL org.opencontainers.image.title="autogen-core-java" \
      org.opencontainers.image.description="多智能体控制台（Java 复刻 AutoGen core）" \
      org.opencontainers.image.licenses="MIT"

# curl 只为 HEALTHCHECK：/api/health 要口令，探活得能自己带上
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# 非 root 运行：容器被攻破时，攻击者拿到的只是一个对 /app 只读的普通用户。
# uid 固定成 10001，Kubernetes 的 runAsUser / 卷的 fsGroup 才能对上。
RUN groupadd --gid 10001 app \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app
COPY --from=backend /source/target/lib ./lib
COPY --from=backend /source/target/*.jar ./app.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh

# 数据目录（会话 / 日志 / 快照）在这里。先建好并 chown，
# 命名卷第一次创建时 Docker 会把这份属主复制进卷，非 root 用户才写得进去。
RUN mkdir -p /data/snapshots /data/conversations /data/logs /workspace \
    && chown -R app:app /data /workspace /app

# 容器内必须绑 0.0.0.0，否则宿主机访问不到；
# 绑定非回环地址时启动会强制要求 SERVER_AUTH_TOKEN，没配直接拒绝启动。
ENV SERVER_HOST=0.0.0.0 \
    SERVER_PORT=8097 \
    SERVER_SNAPSHOT_DIR=/data/snapshots \
    WEB_DATA_DIR=/data \
    WEB_LOG_DIR=/data/logs \
    HOME=/tmp \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

USER 10001:10001
WORKDIR /app
EXPOSE 8097

HEALTHCHECK --interval=30s --timeout=5s --start-period=25s --retries=3 \
    CMD curl -fsS -o /dev/null "http://127.0.0.1:${SERVER_PORT}/api/health?token=${SERVER_AUTH_TOKEN}" || exit 1

ENTRYPOINT ["/app/docker-entrypoint.sh"]
