# workhorse-agent 容器镜像（变更 dataweave-managed-sidecar，tasks 5.2）。
#
# 修复 docker-compose `workhorse` 服务此前指向不存在镜像的悬空 `image:`。这是 Linux 容器化部署的
# 可选路径——默认推荐 managed 进程托管（agent.workhorse.managed=true，DataWeave 后端直接拉起宿主二进制，
# 跨 Win/Mac/Linux 一致）；compose 路线与 managed 二选一，勿同开（端口 8300 会互斥）。
#
# 前置：先 `deploy/workhorse/fetch-bin.sh` 产出 deploy/workhorse/bin/workhorse-agent-linux-amd64。
# 构建上下文须为仓库根（compose 已配 context: .）。

# ── stage 1：取预编译 linux 二进制（不在镜像内编译 Go，对齐 D4 二进制为交付物）──
FROM busybox:1.36 AS bin
ARG TARGET_BINARY=deploy/workhorse/bin/workhorse-agent-linux-amd64
COPY ${TARGET_BINARY} /workhorse-agent
RUN chmod +x /workhorse-agent

# ── stage 2：最小运行时 ──
FROM gcr.io/distroless/base-debian12
COPY --from=bin /workhorse-agent /usr/local/bin/workhorse-agent
COPY deploy/workhorse/config.yaml /etc/workhorse/config.yaml
COPY deploy/workhorse/mcp.json /etc/workhorse/mcp.json
EXPOSE 8300
ENTRYPOINT ["/usr/local/bin/workhorse-agent"]
CMD ["serve", "--config", "/etc/workhorse/config.yaml", "--host", "127.0.0.1", "--port", "8300"]
