"""vLLM 连续批处理吞吐 benchmark（对照 transformers eager 的 388 tok/s 地板）。
同一部署模型 run-tri-3b-lw3/merged、同真实脚本、同确定性解码(temperature=0)。

注意：vLLM v1 引擎用 spawn 起 EngineCore 子进程，所有执行体必须放在
`if __name__ == "__main__":` 守卫内，否则子进程重导入会递归 spawn 报 bootstrapping 错。"""
import json
import time
import multiprocessing as mp
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODEL = str(ROOT / "out/run-tri-3b-lw3/merged")
SYSTEM = ("You are a data lineage extractor for ETL scripts. Given a PYTHON or SHELL task "
          "script, output ONLY a JSON object {\"reads\": [...], \"writes\": [...]} where each "
          "item is {\"table\": str, \"columns\": [str] or null}. Rules: include a table only if "
          "its literal name appears in the script text; ignore dynamically-built table names, "
          "commented-out SQL, and SQL that is merely printed or logged; if nothing is read or "
          "written, output {\"reads\": [], \"writes\": []}.")


def main():
    from vllm import LLM, SamplingParams

    rows = [json.loads(l) for l in (ROOT / "realeval/gold/real-c-tri.jsonl").read_text().splitlines() if l.strip()]
    convs = [[{"role": "system", "content": SYSTEM},
              {"role": "user", "content": f"task_type: python\nscript:\n{r['content'][:4000]}"}] for r in rows]

    llm = LLM(model=MODEL, dtype="bfloat16", gpu_memory_utilization=0.85, max_model_len=2048,
              enforce_eager=False)
    sp = SamplingParams(temperature=0.0, max_tokens=256)

    # 预热
    llm.chat(convs[:2], sp, use_tqdm=False)

    # ── 全量一次性喂入（连续批处理自动调度）──
    t0 = time.perf_counter()
    outs = llm.chat(convs, sp, use_tqdm=False)
    wall = time.perf_counter() - t0
    n_out = sum(len(o.outputs[0].token_ids) for o in outs)
    n_in = sum(len(o.prompt_token_ids) for o in outs)
    req_s = len(convs) / wall
    tok_s = n_out / wall

    # ── 强制等长 128 token 峰值 decode 吞吐（对照 transformers 388 tok/s）──
    sp128 = SamplingParams(temperature=0.0, min_tokens=128, max_tokens=128)
    t1 = time.perf_counter()
    outs2 = llm.chat([convs[3]] * 64, sp128, use_tqdm=False)
    wall2 = time.perf_counter() - t1
    peak_tok_s = sum(len(o.outputs[0].token_ids) for o in outs2) / wall2

    print("\n===== vLLM 连续批处理（RTX 5070 sm_120）=====")
    print(f"  全量 {len(convs)} 条一次喂入：{wall:.2f}s → {req_s:.1f} req/s、{tok_s:.0f} output-tok/s")
    print(f"  （均输入 {n_in/len(convs):.0f} tok、输出 {n_out/len(convs):.0f} tok/req）")
    print(f"  强制等长 128×64：{wall2:.2f}s → 峰值 {peak_tok_s:.0f} decode-tok/s")
    print(f"RESULT_VLLM {json.dumps({'req_s': req_s, 'tok_s': tok_s, 'peak_decode_tok_s': peak_tok_s, 'n': len(convs), 'mean_in': n_in/len(convs), 'mean_out': n_out/len(convs)})}")


if __name__ == "__main__":
    mp.freeze_support()
    main()
