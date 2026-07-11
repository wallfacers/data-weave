#!/usr/bin/env bash
# 在宿主机安装 Apache Flink 客户端（与 Docker flink:1.20 同版本）。
# 用法：sudo ./clients/install-flink.sh [version] [install_dir]
# 版本默认 1.20.0（与 flink:1.20 Docker 镜像一致），安装到 /opt/flink。
# 产物：FLINK_HOME/bin/flink 可用；conf/flink-conf.yaml 配置 rest.port=8083。
set -euo pipefail

FLINK_VERSION="${1:-1.20.0}"
SCALA_VERSION="${2:-2.12}"
INSTALL_DIR="${3:-/opt/flink}"

TARBALL="flink-${FLINK_VERSION}-bin-scala_${SCALA_VERSION}.tgz"
URL="https://archive.apache.org/dist/flink/flink-${FLINK_VERSION}/${TARBALL}"

echo "[install-flink] 版本=${FLINK_VERSION} scala=${SCALA_VERSION} 安装到=${INSTALL_DIR}"

if [ -f "${INSTALL_DIR}/bin/flink" ]; then
    echo "[install-flink] 已安装"
    "${INSTALL_DIR}/bin/flink" --version 2>&1 | head -3
    echo "[install-flink] 跳过下载。如需重装，先 rm -rf ${INSTALL_DIR}"
    exit 0
fi

TMPDIR="$(mktemp -d)"
trap "rm -rf ${TMPDIR}" EXIT

cd "${TMPDIR}"
echo "[install-flink] 下载 ${URL} ..."
curl -sSL --connect-timeout 30 --max-time 600 -o "${TARBALL}" "${URL}"

echo "[install-flink] 解压到 ${INSTALL_DIR} ..."
sudo mkdir -p "${INSTALL_DIR}"
sudo tar xzf "${TARBALL}" -C "${INSTALL_DIR}" --strip-components=1

# 配置 REST 端口：本环境 Flink JM 在 8083（8081 被 Spark worker 占）
echo "[install-flink] 配置 rest.port=8083（本环境 Flink JM 在 8083）..."
CONF="${INSTALL_DIR}/conf/flink-conf.yaml"
sudo sed -i 's/^rest.port:.*/rest.port: 8083/' "${CONF}" 2>/dev/null || {
    # 文件可能不存在或无权限
    echo "rest.port: 8083" | sudo tee -a "${CONF}" > /dev/null
}
# 确认 rest.address 为 localhost
sudo sed -i 's/^rest.address:.*/rest.address: localhost/' "${CONF}" 2>/dev/null || true

echo "[install-flink] 验证 flink ..."
"${INSTALL_DIR}/bin/flink" --version 2>&1 | head -5

echo "[install-flink] 完成。FLINK_HOME=${INSTALL_DIR}"
echo "export FLINK_HOME=${INSTALL_DIR}" >> ~/.bashrc
export FLINK_HOME="${INSTALL_DIR}"
