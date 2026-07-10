#!/usr/bin/env bash
# 统一真跑证据抓取 + 台账 upsert。
# 用法：
#   capture.sh <engine> <success|fail|skipped> <exit_code> <raw_log_path> <engine_version> [run_via] [extra_json]
# 行为：
#   1) 把 raw_log 脱敏成 evidence/<engine>/<kind>.log
#   2) upsert 一行进 evidence/ledger.json（结构遵 contracts/evidence-ledger.schema.json）
# 依赖：python3（免 jq）。凭据脱敏：屏蔽 password/pwd/token/secret 明文。
set -euo pipefail

ENGINE="${1:?engine}"; KIND="${2:?success|fail|skipped}"; EXIT_CODE="${3:?exit_code}"
RAW="${4:?raw_log_path}"; VER="${5:?engine_version}"; RUN_VIA="${6:-dw run}"; EXTRA="${7:-{}}"

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
EVID="$REPO/specs/061-task-type-verification/evidence"
LEDGER="$EVID/ledger.json"
mkdir -p "$EVID/$ENGINE"

OUT="$EVID/$ENGINE/$KIND.log"
sed -E 's/(password|pwd|passwd|token|secret)([=: ]+)[^ "'\'';,]+/\1\2***REDACTED***/Ig' "$RAW" > "$OUT"
echo "[capture] evidence → $OUT (exit=$EXIT_CODE, ver=$VER)"

REL="evidence/$ENGINE/$KIND.log"
FIELD="${KIND}_evidence"

python3 - "$LEDGER" "$ENGINE" "$FIELD" "$REL" "$VER" "$RUN_VIA" "$EXTRA" <<'PY'
import json, sys, os
ledger, eng, field, rel, ver, via, extra = sys.argv[1:8]
try:
    extra = json.loads(extra or "{}")
except Exception:
    extra = {}
if os.path.exists(ledger):
    with open(ledger) as f: data = json.load(f)
else:
    data = {"feature":"061-task-type-verification","generated_at":None,"overall":"INCOMPLETE","entries":[]}
entries = data.setdefault("entries", [])
row = next((e for e in entries if e.get("engine")==eng), None)
if row is None:
    row = {"engine":eng}; entries.append(row)
row.update({"engine_version":ver, "run_via":via, field:rel})
row.update(extra)
with open(ledger,"w") as f: json.dump(data, f, ensure_ascii=False, indent=2)
print(f"[capture] ledger upsert: {eng}.{field}")
PY
