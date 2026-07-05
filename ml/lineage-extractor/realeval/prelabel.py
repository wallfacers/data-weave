"""041-R 真实集双模型交叉预标：M1+M2+规则各自独立预标，一致=高置信，分歧=高亮终审。

`cross_prelabel` 是纯逻辑（只依赖传入的 `predictors: {name: predict_fn}`），本模块
不在测试路径上触网；真实预标由 `main()` 编排：读 `realeval/pool/*.json`，用
`llm.clients.load_clients()` + `eval.baselines.{llm_baseline,regex_baseline}` 组装
predictors（缺失的大模型 client 优雅跳过，不报错退出），逐条交叉预标后写
`realeval/tolabel/*.json`，供人工终审产出 `realeval/gold/*.jsonl`。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from eval.metrics import tables

DEFAULT_POOL = "realeval/pool"
DEFAULT_OUT = "realeval/tolabel"


# 复审投票者：优先用大模型（m1/m2）判定分歧——正则基线太糙，会把注释里的英文词
# （FROM the / standard SELECT …）误当表名，若与 LLM 并列投票会把复审队列灌满假分歧。
# 故 LLM≥2 时只看 LLM 之间是否一致定 needs_review；不足 2 个 LLM 才退回全体投票者。
_LLM_VOTERS = ("m1", "m2")


def cross_prelabel(predictors: dict, row: dict) -> dict:
    preds = {name: fn(row) for name, fn in predictors.items()}
    read_sets = [tables(p.get("reads")) for p in preds.values()]
    write_sets = [tables(p.get("writes")) for p in preds.values()]
    agree_r = all(s == read_sets[0] for s in read_sets)
    agree_w = all(s == write_sets[0] for s in write_sets)

    # 复审判据：以 LLM 投票者为主，规则基线仅作参考展示（仍进 predictions/union）。
    voters = [n for n in preds if n in _LLM_VOTERS]
    if len(voters) < 2:
        voters = list(preds)
    v_reads = [tables(preds[n].get("reads")) for n in voters]
    v_writes = [tables(preds[n].get("writes")) for n in voters]
    llm_consensus = (all(s == v_reads[0] for s in v_reads)
                     and all(s == v_writes[0] for s in v_writes))

    def _u(sets):
        u = set().union(*sets) if sets else set()
        return sorted(u)

    def _i(sets):
        i = set(sets[0]).intersection(*sets) if sets else set()
        return sorted(i)

    return {"predictions": preds,
            "agreement": {"reads": agree_r, "writes": agree_w},  # 全体一致（含规则，参考用）
            "review_voters": voters,
            "llm_consensus": llm_consensus,  # 复审投票者（优先 LLM）是否一致
            "union": {"reads": _u(read_sets), "writes": _u(write_sets)},
            "intersection": {"reads": _i(read_sets), "writes": _i(write_sets)},
            "needs_review": not llm_consensus}


def _infer_task_type(record: dict) -> str:
    """pool 记录只有 content/source，无 task_type；按文件扩展名粗略推断，供
    llm_baseline 的 predict(row) 组装 prompt 用，默认 PYTHON。"""
    path = ((record.get("source") or {}).get("path") or "").lower()
    if path.endswith((".sh", ".bash")):
        return "SHELL"
    if path.endswith((".scala", ".sc")):
        return "SCALA"
    if path.endswith(".java"):
        return "JAVA"
    return "PYTHON"


def _build_predictors() -> dict:
    """组装 predictors：m1/m2 大模型基线 + rule 正则基线。缺失的 client（.env 未配置
    对应大模型凭据）优雅跳过该 predictor，而非报错退出。"""
    from eval.baselines import llm_baseline, regex_baseline
    from llm.clients import load_clients

    predictors = {"rule": regex_baseline.predict}
    clients = load_clients()
    llm_count = 0
    for name in ("m1", "m2"):
        client = clients.get(name)
        if client is not None:
            predictors[name] = llm_baseline.make_predict(client)
            llm_count += 1
        else:
            print(f"[warn] {name} client 缺失，交叉预标跳过该 predictor")
    if llm_count == 0:
        # 只剩规则基线=单一投票者，llm_consensus 恒真→needs_review 恒假，复审队列会静默清零。
        print("[warn] 无可用 LLM 预标器（m1/m2 均缺失）：交叉预标退化为仅规则基线，"
              "needs_review 分诊失效、复审队列将全空——请配置 .env 中的大模型凭据后重跑。")
    elif llm_count < 2:
        print(f"[warn] 仅 {llm_count} 个 LLM 预标器可用：分歧判据退回全体投票者（含规则噪声），分诊精度下降。")
    return predictors


def prelabel_pool(predictors: dict, pool_dir: Path, out_dir: Path, job_only: bool = True) -> int:
    """对 pool_dir 下每条候选做交叉预标，写 out_dir/*.json。

    `job_only=True`（默认）跳过 `meta.looks_like_job==False` 的库/测试源码候选
    （分层 query 会命中框架源码，其表名是代码示例非血缘作业），并 log 跳过数——
    不静默截断。无 meta 的旧记录（试水）视为作业保留。

    needs_review=True（分歧）排前，且文件名带零填充 rank 前缀（如 `0000_xxx.json`），
    使 `ls`/文件系统的字母序与 triage 顺序一致——排序只发生在内存里对磁盘产物无效，
    人工终审按目录浏览时看不到"分歧置顶"，故必须靠文件名前缀落到磁盘上。
    同时写 `_index.json`（[{rank, filename, needs_review}, ...]）供工具消费。
    返回写入条数。
    """
    out_dir.mkdir(parents=True, exist_ok=True)
    items = []
    skipped = 0
    for path in sorted(pool_dir.glob("*.json")):
        record = json.loads(path.read_text(encoding="utf-8"))
        if job_only and record.get("meta", {}).get("looks_like_job") is False:
            skipped += 1
            continue
        row = {"task_type": _infer_task_type(record), "content": record["content"]}
        result = cross_prelabel(predictors, row)
        items.append((path.stem, {**record, "task_type": row["task_type"], "prelabel": result}))
    if skipped:
        print(f"[info] job_only 过滤：跳过 {skipped} 条库/测试源码候选（非 ETL 作业，不入终审队列）")

    # 分歧(needs_review=True)优先；稳定次级键用 stem，保证同类内确定性顺序
    items.sort(key=lambda kv: (not kv[1]["prelabel"]["needs_review"], kv[0]))

    index = []
    for rank, (stem, out_record) in enumerate(items):
        filename = f"{rank:04d}_{stem}.json"
        (out_dir / filename).write_text(
            json.dumps(out_record, ensure_ascii=False, indent=2), encoding="utf-8")
        index.append({
            "rank": rank,
            "filename": filename,
            "needs_review": out_record["prelabel"]["needs_review"],
        })

    (out_dir / "_index.json").write_text(
        json.dumps(index, ensure_ascii=False, indent=2), encoding="utf-8")

    return len(items)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="041-R 真实集双模型交叉预标")
    parser.add_argument("--pool", default=DEFAULT_POOL, help="候选池目录（Task 11 采集产出）")
    parser.add_argument("--out", default=DEFAULT_OUT, help="预标输出目录")
    args = parser.parse_args(argv)

    predictors = _build_predictors()
    written = prelabel_pool(predictors, Path(args.pool), Path(args.out))
    print(f"交叉预标完成：写入 {written} 条到 {args.out}（分歧样本 needs_review=true 已置顶）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
