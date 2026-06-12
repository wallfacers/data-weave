# dataweave-api / master 运行镜像（distributed 模式 master 进程）
# 构建上下文为本模块目录；jar 由宿主 `./mvnw -pl dataweave-api -am package -DskipTests` 预构建。
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
# Spring Boot 可执行 jar（无 classifier，repackage 替换主 artifact）
COPY target/dataweave-api-*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
