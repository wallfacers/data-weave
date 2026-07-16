# workhorse-agent 容器镜像（071 运行态 companion 大脑 sidecar）。
# 复用 deploy/workhorse/bin/ 预编译二进制（源码最新提交已 build，无需 go 工具链）；
# config.yaml 模板无敏感，secret 经 env 注入（见 config.yaml 注释）。
# build context = ./deploy/workhorse
FROM alpine:3
RUN apk add --no-cache bash ca-certificates
WORKDIR /app
COPY bin/workhorse-agent-linux-amd64 /app/workhorse-agent
COPY config.yaml /app/config.yaml
COPY mcp.json /app/mcp.json
COPY agents /app/agents
RUN chmod +x /app/workhorse-agent
EXPOSE 8300
ENTRYPOINT ["/app/workhorse-agent"]
CMD ["serve", "--config", "/app/config.yaml", "--host", "0.0.0.0", "--port", "8300"]
