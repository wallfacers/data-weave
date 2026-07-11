#!/usr/bin/env bash
# 在宿主机安装 Apache Spark 客户端（与 Docker Spark 3.5 standalone 同代）。
# 用法：sudo ./clients/install-spark.sh [version] [install_dir]
# 版本默认 3.5.4（与 apache/spark:3.5 Docker 镜像版本一致），安装到 /opt/spark。
# 产物：SPARK_HOME/bin/spark-submit 可用。
set -euo pipefail

SPARK_VERSION="${1:-3.5.4}"
HADOOP_PROFILE="${2:-hadoop3}"
INSTALL_DIR="${3:-/opt/spark}"

TARBALL="spark-${SPARK_VERSION}-bin-${HADOOP_PROFILE}.tgz"
URL="https://archive.apache.org/dist/spark/spark-${SPARK_VERSION}/${TARBALL}"

echo "[install-spark] 版本=${SPARK_VERSION} hadoop=${HADOOP_PROFILE} 安装到=${INSTALL_DIR}"

if [ -f "${INSTALL_DIR}/bin/spark-submit" ]; then
    echo "[install-spark] 已安装：$(${INSTALL_DIR}/bin/spark-submit --version 2>&1 | head -2)"
    echo "[install-spark] 跳过下载。如需重装，先 rm -rf ${INSTALL_DIR}"
    exit 0
fi

TMPDIR="$(mktemp -d)"
trap "rm -rf ${TMPDIR}" EXIT

cd "${TMPDIR}"
echo "[install-spark] 下载 ${URL} ..."
curl -sSL --connect-timeout 30 --max-time 600 -o "${TARBALL}" "${URL}"

echo "[install-spark] 解压到 ${INSTALL_DIR} ..."
sudo mkdir -p "${INSTALL_DIR}"
sudo tar xzf "${TARBALL}" -C "${INSTALL_DIR}" --strip-components=1

echo "[install-spark] 验证 spark-submit ..."
"${INSTALL_DIR}/bin/spark-submit" --version 2>&1 | head -5

echo "[install-spark] 完成。SPARK_HOME=${INSTALL_DIR}"
echo "export SPARK_HOME=${INSTALL_DIR}" >> ~/.bashrc
export SPARK_HOME="${INSTALL_DIR}"
