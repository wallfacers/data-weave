# dataweave-worker 运行镜像（distributed 模式独立 worker 进程，task 3.7）
# 构建上下文为本模块目录；fat jar 由宿主 `./mvnw -pl dataweave-worker -am package -DskipTests` 预构建。
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
# bash 用于 ShellTaskExecutor（alpine 默认无 bash）
RUN apk add --no-cache bash
# Spring Boot 可执行 jar（classifier=exec，区别于供 sibling 依赖的原始 jar）
COPY target/dataweave-worker-*-exec.jar /app/app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
