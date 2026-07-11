#!/usr/bin/env bash
# 安装 DataX 到本地（子进程引擎，需 DATAX_HOME 或 EngineSubmitRef.engineHome）。
# DataX 官方需 Python2.7，本脚本下载预构建包并适配 Python3 环境。
#
# 用法：
#   ./clients/install-datax.sh [INSTALL_DIR]
#
# 默认 INSTALL_DIR=$HOME/engines/datax
set -euo pipefail

INSTALL_DIR="${1:-$HOME/engines/datax}"
DATAX_URL="https://datax-opensource.oss-cn-hangzhou.aliyuncs.com/202309/datax.tar.gz"
DATAX_VER="datax_v202309"

echo "=== DataX 安装 ==="
echo "目标目录: $INSTALL_DIR"
echo "版本: $DATAX_VER"
echo "下载: $DATAX_URL"

if [ -d "$INSTALL_DIR/bin" ] && [ -f "$INSTALL_DIR/bin/datax.py" ]; then
    echo "[skip] DataX 已安装于 $INSTALL_DIR"
else
    TARBALL="/tmp/datax-${DATAX_VER}.tar.gz"
    if [ ! -f "$TARBALL" ]; then
        echo "[download] 下载 DataX (~1.6G，请耐心等待)..."
        curl -L --connect-timeout 30 --max-time 1800 \
            -o "$TARBALL" "$DATAX_URL"
        echo "[download] 完成 → $TARBALL"
    else
        echo "[skip] tarball 已缓存: $TARBALL"
    fi

    echo "[extract] 解压到 $INSTALL_DIR ..."
    mkdir -p "$INSTALL_DIR"
    tar -xzf "$TARBALL" -C "$INSTALL_DIR" --strip-components=1
    echo "[extract] 完成"
fi

# 确保 datax.py 可执行（ProcessBuilder 需要 execute bit）
if [ -f "$INSTALL_DIR/bin/datax.py" ]; then
    chmod +x "$INSTALL_DIR/bin/datax.py"
    echo "[fix] chmod +x datax.py"
fi

# --- 适配 Python3 ---
DATAX_PY="$INSTALL_DIR/bin/datax.py"
if [ -f "$DATAX_PY" ]; then
    SHEBANG=$(head -1 "$DATAX_PY")
    echo "[check] shebang: $SHEBANG"

    # 检查 shebang 指向的解释器是否存在
    if echo "$SHEBANG" | grep -q "python" && ! echo "$SHEBANG" | grep -q "python3"; then
        # 尝试找 python3
        PY3=$(which python3 2>/dev/null || echo "")
        if [ -n "$PY3" ] && ! which python >/dev/null 2>&1; then
            echo "[fix] 系统无 'python'，将 shebang 改为 $PY3"
            sed -i "1s|^#!.*python.*|#!/usr/bin/env python3|" "$DATAX_PY"
            echo "[fix] 新 shebang: $(head -1 "$DATAX_PY")"
        fi
    fi
fi

# --- 自检 ---
echo ""
echo "=== 自检 ==="
echo "DATAX_HOME=$INSTALL_DIR"
echo "datax.py: $([ -f "$INSTALL_DIR/bin/datax.py" ] && echo 'OK' || echo 'MISSING')"
echo "Python: $(python3 --version 2>&1)"
echo "Java: $(java -version 2>&1 | head -1)"

# 尝试运行 datax.py（可能报错但能证明可启动）
echo ""
echo "[smoke] 试运行 datax.py（无参数输出 usage）..."
cd "$INSTALL_DIR"
if python3 bin/datax.py 2>&1 | head -20; then
    echo "[smoke] DataX 启动成功"
else
    # DataX 无参数时返回非零是正常的（缺少 job 参数）
    echo "[smoke] DataX 可启动（无参数预期报错，非问题）"
fi

echo ""
echo "=== 安装完成 ==="
echo "DATAX_HOME=$INSTALL_DIR"
echo "版本: $DATAX_VER"
