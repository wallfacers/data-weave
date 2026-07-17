# dataweave-api / master 运行镜像（distributed 模式 master 进程）
# 构建上下文为本模块目录；jar 由宿主 `./mvnw -pl dataweave-api -am package -DskipTests` 预构建。
# 启动加速（重启风暴下 146s+ → 目标 <30s，防 worker self-fence 120s 窗口越界）：
#   ① 解包布局（jarmode tools extract）：消除嵌套 fat-jar 的随机读，类加载走普通 classpath；
#   ② JDK 25 AOT cache：构建期用 h2 profile 跑一次 context refresh 训练（spring.context.exit=onRefresh），
#      类加载/链接固化为单个顺序读缓存文件；运行期 -XX:AOTCache 命中，缓存失效时 JVM 静默回退不影响功能。
#   训练与运行的 JVM flags/路径必须一致（同为 /app/app），否则缓存被静默丢弃。
FROM eclipse-temurin:25-jre-alpine AS builder
WORKDIR /build
COPY target/dataweave-api-*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --destination /app/app
WORKDIR /app
RUN SPRING_PROFILES_ACTIVE=h2 java -XX:AOTCacheOutput=/app/app.aot \
    --enable-native-access=ALL-UNNAMED \
    -Dspring.context.exit=onRefresh -jar /app/app/app.jar

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=builder /app/app /app/app
COPY --from=builder /app/app.aot /app/app.aot
EXPOSE 8080
ENTRYPOINT ["java", "-XX:AOTCache=/app/app.aot", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/app/app.jar"]
