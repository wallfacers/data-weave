#!/usr/bin/env bash
# DataWeave 后端 · 本地开发快速安装
#
# 自动优先用 mvnd(Maven 守护进程：常驻 JVM 省冷启动 + 多核并行)，未安装则回退 mvnw。
# 跳过测试编译与 fat jar 打包(开发期 spring-boot:run 不需要 fat jar)，大幅缩短增量构建。
# 构建缓存由 .mvn/extensions.xml 自动启用，未改动的模块命中缓存不重编。
#
#   用法：
#     ./dev-install.sh                            # 全量装四模块到本地仓库
#     ./dev-install.sh -pl dataweave-master -am   # 只装某模块及其上游(更快)
#   跑：  mvnd -pl dataweave-api spring-boot:run   (或 ./mvnw -pl dataweave-api spring-boot:run)
#
# ⚠️ 仅限本地开发。CI / 打部署包请用 `./mvnw install`(需跑测试 + 生成可执行 fat jar)。
set -euo pipefail
cd "$(dirname "$0")"

FLAGS=(-T 1C -Dmaven.test.skip=true -Dspring-boot.repackage.skip=true)

if command -v mvnd >/dev/null 2>&1; then
  echo "→ mvnd install ${FLAGS[*]} $*"
  exec mvnd install "${FLAGS[@]}" "$@"
else
  echo "→ 未检测到 mvnd，回退 mvnw(建议装 mvnd 提速，见 README『本地构建提速』)"
  echo "  注意：mvnw 需 JAVA_HOME 指向 JDK 25，否则 release=25 编译失败。"
  exec ./mvnw install "${FLAGS[@]}" "$@"
fi
