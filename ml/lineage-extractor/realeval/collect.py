"""041-R 真实 ETL 脚本采集：GitHub code search → license 过滤 → 脱敏 → 候选池。

设计原则：
- `sanitize` / `is_redistributable` / `dedup_key` 为纯函数，import 本模块不产生任何
  网络副作用，可离线单测。
- `main()` 是采集主流程骨架：调用 GitHub code search API，逐条做 license 过滤 +
  脱敏 + 去重，写入 `realeval/pool/*.json`（含 provenance）。真实联网采集需要
  `GITHUB_TOKEN` 环境变量；缺失时直接报错退出，不做静默 mock。
"""

import argparse
import hashlib
import json
import os
import re
import sys
import time
from pathlib import Path

_SECRET = [
    re.compile(r"-h\s+\d+\.\d+\.\d+\.\d+"),
    re.compile(r"\w*password[=:\s]+\S+", re.I),
    re.compile(r"\b(?:token|apikey|api_key|secret)[=:]\s*\S+", re.I),
    # 连接串权威段：`scheme://[user[:pass]@]host[:port]` 整体脱敏——覆盖
    # ① 内联凭据 user:pass@（旧规则仅此项）② 裸主机名（如 RDS/内网 endpoint，设计「去 host」）。
    # 只吃到 path 前，保留 `/db` 等路径；SQL 里的表名（无 `://`）不受影响。
    re.compile(r"(?i)://(?:[^/\s\"'@]+@)?[a-z0-9][a-z0-9_.\-]*(?::\d+)?"),
    re.compile(r"\b\d{1,3}(?:\.\d{1,3}){3}\b"),
]

_OK_LICENSE = {"apache-2.0", "mit", "bsd-2-clause", "bsd-3-clause", "isc"}

GITHUB_API = "https://api.github.com"
DEFAULT_QUERY = "INSERT INTO language:python"
DEFAULT_OUT = "realeval/pool"

# 分层采集：字面密集 vs 自然采样。
# - LITERAL_RICH：精准打模型学过的 idiom（wrapper 动词 / INSERT OVERWRITE / copy_into /
#   spark.sql INSERT / hive -e / bq query），命中脚本的表名多为字面 → 评测集真正考抽取
#   （recall/precision/direction），而非只考低幻觉。
# - NATURAL：宽查询，保留真实分布 → 量化"真实 ETL 参数化率"这一论文 finding。
# 均限 python/shell，与 prelabel 的 task_type 推断（仅 SHELL/PYTHON）对齐。
LITERAL_RICH_QUERIES = [
    "saveAsTable language:python",
    '"INSERT OVERWRITE TABLE" language:python',
    "insertInto language:python",
    "copy_into language:python",
    '"spark.sql" "INSERT INTO" language:python',
    '"hive -e" "INSERT" language:shell',
    '"bq query" "SELECT" language:shell',
    '"psql" "COPY" language:shell',
]
NATURAL_QUERIES = [
    "INSERT INTO language:python",
    "etl load language:shell",
]
# 041-R 加强实验 A：扩真实集。新增一组字面丰富查询，覆盖更多引擎/写法，
# 命中与原 8 条不同的仓库/习语，把非空金标从 18 firm up 到 ≥50。
# 保留上方原查询不动，确保原 run 分层统计可复现。
EXPANDED_QUERIES = [
    '"MERGE INTO" language:sql',
    '"CREATE TABLE AS" language:python',
    '"writeTo" "append" language:python',
    '".write.saveAsTable" language:python',
    '".to_sql" language:python',
    '"LOAD DATA INPATH" language:sql',
    '"COPY INTO" language:sql',
    '"UNLOAD" "TO" language:python',
    '"spark.sql" "CREATE TABLE" language:python',
    '"hive -e" "INSERT OVERWRITE" language:shell',
    '"beeline" "-e" "INSERT" language:shell',
    '"bq load" language:shell',
    '"mysql -e" "INSERT" language:shell',
    '"snowsql" "-q" language:shell',
]
# 041-R JVM 扩语言：Scala/Java 的 Spark + Flink 作业。表名同样在 API 调用/内嵌 SQL
# 字符串里字面出现（spark.sql/saveAsTable/writeTo/insertInto/executeSql）。与 prelabel
# 的 task_type 推断（.scala→SCALA / .java→JAVA）对齐。字面表名的 JVM ETL 稀疏，尽力而为。
JVM_QUERIES = [
    '"spark.sql" "INSERT" language:scala',
    '".saveAsTable" language:scala',
    '".writeTo" "append" language:scala',
    '".insertInto" language:scala',
    '"tableEnv.executeSql" "INSERT" language:scala',
    '"spark.sql" "INSERT" language:java',
    '".saveAsTable" language:java',
    '"tableEnv.executeSql" "INSERT" language:java',
    '".insertInto" language:java',
    '"executeInsert" language:java',
]
QUERY_PROFILES = {
    "literal": LITERAL_RICH_QUERIES,
    "natural": NATURAL_QUERIES,
    "expanded": EXPANDED_QUERIES,
    "jvm": JVM_QUERIES,
    "mixed": LITERAL_RICH_QUERIES + NATURAL_QUERIES,
    "wide": LITERAL_RICH_QUERIES + NATURAL_QUERIES + EXPANDED_QUERIES,
}


def sanitize(text: str) -> str:
    """脱敏：去 host IP（-h 10.0.0.5）、密码（--password=/PASSWORD:/PGPASSWORD=等）、
    token/apikey/secret、URI 内联凭据（user:pass@host）、内网 IP。"""
    for p in _SECRET:
        text = p.sub("<redacted>", text)
    return text


def is_redistributable(license_id: str) -> bool:
    """仅 Apache-2.0 / MIT / BSD-2/3-Clause / ISC 视为可再分发。GPL 系列不可用。"""
    return (license_id or "").strip().lower() in _OK_LICENSE


def dedup_key(content: str) -> str:
    """空白归一化 + 小写后取 sha256 前 16 位，用于跨仓库内容去重。"""
    norm = re.sub(r"\s+", " ", content.strip().lower())
    return hashlib.sha256(norm.encode()).hexdigest()[:16]


def literal_density(content: str) -> int:
    """脚本里可被规则基线抽到的**字面**表名去重计数——网络无关的确定性代理指标。

    高 = 表名多为字面（`INSERT INTO ods.a … FROM dwd.b`）→ 该脚本能真正考抽取；
    低/0 = 表名被参数化（`f"INSERT INTO {tbl}"`、Jinja、shell 变量）→ 只考低幻觉。
    用于分层采集与统计真实 ETL 参数化率。注意：仅 regex_baseline 覆盖的 idiom
    计入（FROM/JOIN/INSERT/saveAsTable…），copy_into/reads= 等未计，故为**下界**代理。"""
    from eval.baselines.regex_baseline import predict

    p = predict({"content": content})
    tabs = {t["table"] for t in p["reads"]} | {t["table"] for t in p["writes"]}
    return len(tabs)


_LIB_PATH_HINTS = re.compile(
    r"(?:^|/)(?:site-packages|dist-packages|node_modules|vendor|third_party|"
    r"pyspark|sqlglot|sqlalchemy|sqlparse|fakesnow|pandas|numpy)(?:/|$)", re.I)
_TEST_PATH_HINTS = re.compile(r"(?:^|/)(?:tests?|testing|__tests__)/|(?:^|/)test_|_test\.", re.I)


def looks_like_etl_job(content: str, path: str = "") -> bool:
    """粗筛：真实 ETL **作业脚本** vs 框架/库源码。

    分层 query（copy_into/saveAsTable…）会命中**实现**这些 idiom 的库本身
    （pyspark/sqlglot/fakesnow 源码），其"表名"是代码示例/测试夹具而非血缘作业——
    正则密度虚高。判据：① 路径命中框架/测试目录 → 否；② shell 脚本几乎都是作业 → 是；
    ③ Python 靠 AST：有顶层调用或 `if __name__=="__main__"` → 作业；只有 class 定义
    无顶层执行 → 库模块。保守：判不准倾向保留(True)，最终人工金标兜底，不在此硬删。"""
    p = path or ""
    if _LIB_PATH_HINTS.search(p) or _TEST_PATH_HINTS.search(p):
        return False
    if p.endswith((".sh", ".bash")):
        return True
    try:
        import ast
        tree = ast.parse(content)
    except (SyntaxError, ValueError):
        return True  # 非 py / 截断 → 保留兜底
    has_call = has_main = has_class = False
    for node in tree.body:
        if isinstance(node, ast.Expr) and isinstance(node.value, ast.Call):
            has_call = True
        elif isinstance(node, ast.ClassDef):
            has_class = True
        elif isinstance(node, ast.If):
            t = node.test
            if (isinstance(t, ast.Compare) and isinstance(t.left, ast.Name)
                    and t.left.id == "__name__"):
                has_main = True
    if has_call or has_main:
        return True
    if has_class:
        return False  # 纯 class 模块 = 库
    return True       # 纯函数/常量模块：可能是脚本工具，保守保留


def _github_headers(token: str) -> dict:
    return {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }


def _search_code(query: str, token: str, limit: int):
    """调用 GitHub code search API，逐页 yield 命中项（file content + repo 元信息）。

    延迟 import requests，保证模块级 import 无网络/依赖副作用。
    """
    import requests

    headers = _github_headers(token)
    per_page = min(30, limit) if limit else 30
    page = 1
    fetched = 0
    while fetched < limit:
        resp = requests.get(
            f"{GITHUB_API}/search/code",
            headers=headers,
            params={"q": query, "per_page": per_page, "page": page},
            timeout=30,
        )
        resp.raise_for_status()
        items = resp.json().get("items", [])
        if not items:
            break
        for item in items:
            if fetched >= limit:
                break
            yield item
            fetched += 1
        page += 1
        time.sleep(1)  # 避免撞 GitHub search API 的速率限制


_repo_license_cache: dict = {}


def _fetch_repo_license(repo_full_name: str, token: str):
    """按 repo_full_name 缓存 license 查询结果，避免同仓库多文件命中时重复请求。"""
    if repo_full_name in _repo_license_cache:
        return _repo_license_cache[repo_full_name]

    import requests

    resp = requests.get(
        f"{GITHUB_API}/repos/{repo_full_name}",
        headers=_github_headers(token),
        timeout=30,
    )
    if resp.status_code != 200:
        license_id = None
    else:
        license_info = resp.json().get("license") or {}
        license_id = license_info.get("spdx_id")

    _repo_license_cache[repo_full_name] = license_id
    return license_id


def _fetch_file_content(item: dict, token: str):
    import base64

    import requests

    url = item.get("url")
    resp = requests.get(url, headers=_github_headers(token), timeout=30)
    resp.raise_for_status()
    data = resp.json()
    if data.get("encoding") == "base64":
        return base64.b64decode(data["content"]).decode("utf-8", errors="replace")
    return data.get("content", "")


def collect(query, token: str, limit: int, out_dir: Path):
    """采集主流程：搜索 → license 过滤 → 拉取内容 → 脱敏 → 去重 → 落盘。

    `query` 可为单个查询串或查询串列表（分层采集）；跨查询共享去重集，`limit`
    为**每查询**上限。每条落盘记录带 `meta.literal_density`（字面表名密度代理）与
    `source.query`（命中它的查询），供分层/参数化率分析。返回写入总条数。"""
    queries = [query] if isinstance(query, str) else list(query)
    out_dir.mkdir(parents=True, exist_ok=True)
    seen_keys = set()
    written = 0

    for q in queries:
        for item in _search_code(q, token, limit):
            repo = item.get("repository", {}) or {}
            repo_full_name = repo.get("full_name")
            if not repo_full_name:
                continue

            license_id = _fetch_repo_license(repo_full_name, token)
            if not is_redistributable(license_id):
                continue

            content = _fetch_file_content(item, token)
            if not content:
                continue

            key = dedup_key(content)
            if key in seen_keys:
                continue
            seen_keys.add(key)

            clean = sanitize(content)
            record = {
                "content": clean,
                "source": {
                    "repo": repo_full_name,
                    "path": item.get("path"),
                    # code-search 返回的是命中文件的 blob SHA，非 commit SHA；
                    # 用 blob_sha 命名以免误导为可用于 `git show <sha>` 的提交号。
                    "blob_sha": (item.get("sha") or ""),
                    "license": license_id,
                    "query": q,
                },
                "meta": {
                    "literal_density": literal_density(clean),
                    "looks_like_job": looks_like_etl_job(clean, item.get("path") or ""),
                },
            }
            out_path = out_dir / f"{key}.json"
            out_path.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
            written += 1

    return written


def main(argv=None):
    parser = argparse.ArgumentParser(description="041-R 真实 ETL 脚本采集器")
    parser.add_argument("--query", default=None, help="单个 GitHub code search 查询串（覆盖 --profile）")
    parser.add_argument("--profile", choices=sorted(QUERY_PROFILES), default=None,
                        help="分层采集查询组：literal（字面密集）/natural（自然）/mixed（两者）")
    parser.add_argument("--limit", type=int, default=20, help="每查询最多采集条数")
    parser.add_argument("--out", default=DEFAULT_OUT, help="候选池输出目录")
    args = parser.parse_args(argv)

    token = os.environ.get("GITHUB_TOKEN")
    if not token:
        print(
            "GITHUB_TOKEN 未设置：真实采集需要具备 public_repo 读权限的 GitHub token。"
            " 例如 `GITHUB_TOKEN=$(gh auth token) python realeval/collect.py`",
            file=sys.stderr,
        )
        return 1

    if args.query:
        queries = args.query
    elif args.profile:
        queries = QUERY_PROFILES[args.profile]
    else:
        queries = DEFAULT_QUERY

    written = collect(queries, token, args.limit, Path(args.out))
    print(f"采集完成：写入 {written} 条候选到 {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
