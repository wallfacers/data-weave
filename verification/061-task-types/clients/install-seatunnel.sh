#!/usr/bin/env bash
# 安装 Apache SeaTunnel 到本地（子进程引擎，需 SEATUNNEL_HOME 或 EngineSubmitRef.engineHome）。
# Zeta 本地引擎模式（-e local），内置 connector-fake/connector-console。
#
# 用法：
#   ./clients/install-seatunnel.sh [INSTALL_DIR] [VERSION]
#
# 默认 INSTALL_DIR=$HOME/engines/seatunnel  VERSION=2.3.13
set -euo pipefail

INSTALL_DIR="${1:-$HOME/engines/seatunnel}"
VERSION="${2:-2.3.13}"
SEATUNNEL_URL="https://archive.apache.org/dist/seatunnel/${VERSION}/apache-seatunnel-${VERSION}-bin.tar.gz"

echo "=== SeaTunnel 安装 ==="
echo "目标目录: $INSTALL_DIR"
echo "版本: $VERSION"
echo "下载: $SEATUNNEL_URL"

if [ -d "$INSTALL_DIR/bin" ] && [ -f "$INSTALL_DIR/bin/seatunnel.sh" ]; then
    echo "[skip] SeaTunnel 已安装于 $INSTALL_DIR"
else
    TARBALL="/tmp/apache-seatunnel-${VERSION}-bin.tar.gz"
    if [ ! -f "$TARBALL" ]; then
        echo "[download] 下载 SeaTunnel (~450M，请耐心等待)..."
        curl -L --connect-timeout 30 --max-time 1800 \
            -o "$TARBALL" "$SEATUNNEL_URL"
        echo "[download] 完成 → $TARBALL"
    else
        echo "[skip] tarball 已缓存: $TARBALL"
    fi

    echo "[extract] 解压到 $INSTALL_DIR ..."
    mkdir -p "$INSTALL_DIR"
    tar -xzf "$TARBALL" -C "$INSTALL_DIR" --strip-components=1
    echo "[extract] 完成"
fi

# --- 确认内置 connector ---
echo ""
echo "=== 自检 ==="
echo "SEATUNNEL_HOME=$INSTALL_DIR"
echo "seatunnel.sh: $([ -f "$INSTALL_DIR/bin/seatunnel.sh" ] && echo 'OK' || echo 'MISSING')"

# 检查内置 connectors
CONN_DIR="$INSTALL_DIR/connectors"
if [ -d "$CONN_DIR" ]; then
    echo "connectors:"
    ls -1 "$CONN_DIR" | grep -i "fake\|console\|jdbc" 2>/dev/null || echo "  (no matching connectors)"
else
    echo "connectors: MISSING (目录 $CONN_DIR 不存在)"
    # 尝试安装 connectors
    if [ -f "$INSTALL_DIR/bin/install-plugin.sh" ]; then
        echo "[plugin] 安装 connector-fake + connector-console ..."
        bash "$INSTALL_DIR/bin/install-plugin.sh" 2>&1 | tail -5 || true
    fi
fi

echo "Java: $(java -version 2>&1 | head -1)"

# 试运行
echo ""
echo "[smoke] 试运行 seatunnel.sh --help ..."
if [ -f "$INSTALL_DIR/bin/seatunnel.sh" ]; then
    bash "$INSTALL_DIR/bin/seatunnel.sh" --help 2>&1 | head -20 || true
else
    echo "[ERROR] seatunnel.sh 不存在！"
    exit 1
fi

echo ""
echo "=== 安装完成 ==="
echo "SEATUNNEL_HOME=$INSTALL_DIR"
echo "版本: $VERSION"
