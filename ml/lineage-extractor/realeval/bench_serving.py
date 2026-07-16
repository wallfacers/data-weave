"""068 成本对比：3B 自部署真实吞吐 benchmark（RTX 5070，真跑真脚本，确定性解码同 serving）。

单流延迟(p50/p99/tokens-s) + 批吞吐(batch 1/4/8/16 的 req/s 与 tokens/s) + 峰值显存。
用真实 gold 脚本(real-c-tri.jsonl)，prompt 格式与 serve/app.py 一致，do_sample=False/max_new=256。
"""
import json
import time
import statistics as st
from pathlib import Path

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer

ROOT = Path(__file__).resolve().parent.parent
MODEL = str(ROOT / "out/run-tri-3b-lw3/merged")
SYSTEM = ("You are a data lineage extractor for ETL scripts. Given a PYTHON or SHELL task "
          "script, output ONLY a JSON object {\"reads\": [...], \"writes\": [...]} where each "
          "item is {\"table\": str, \"columns\": [str] or null}. Rules: include a table only if "
          "its literal name appears in the script text; ignore dynamically-built table names, "
          "commented-out SQL, and SQL that is merely printed or logged; if nothing is read or "
          "written, output {\"reads\": [], \"writes\": []}.")

dev = "cuda"
tok = AutoTokenizer.from_pretrained(MODEL)
if tok.pad_token is None:
    tok.pad_token = tok.eos_token
model = AutoModelForCausalLM.from_pretrained(MODEL, dtype=torch.bfloat16, device_map=dev).eval()

rows = [json.loads(l) for l in (ROOT / "realeval/gold/real-c-tri.jsonl").read_text().splitlines() if l.strip()]
scripts = [r["content"] for r in rows][:40]   # 真实脚本样本


def render(content):
    msgs = [{"role": "system", "content": SYSTEM},
            {"role": "user", "content": f"task_type: python\nscript:\n{content[:4000]}"}]
    return tok.apply_chat_template(msgs, add_generation_prompt=True, tokenize=False)


def gen_batch(batch_scripts):
    """左填充批量确定性生成，返回 (wall_s, total_new_tokens, per_input_len)。"""
    tok.padding_side = "left"
    texts = [render(c) for c in batch_scripts]
    enc = tok(texts, return_tensors="pt", padding=True, add_special_tokens=False).to(dev)
    in_len = enc["input_ids"].shape[1]
    torch.cuda.synchronize()
    t0 = time.perf_counter()
    with torch.no_grad():
        out = model.generate(**enc, max_new_tokens=256, do_sample=False,
                             pad_token_id=tok.pad_token_id)
    torch.cuda.synchronize()
    wall = time.perf_counter() - t0
    gen = out[:, in_len:]
    new_tokens = int((gen != tok.pad_token_id).sum().item())
    return wall, new_tokens, in_len


# 预热（首次 generate 编译 kernel）
for _ in range(2):
    gen_batch(scripts[:1])
torch.cuda.reset_peak_memory_stats()

# ── 单流延迟 ──
lat, outs = [], []
for c in scripts[:20]:
    w, n, _ = gen_batch([c])
    lat.append(w); outs.append(n)
p50 = st.median(lat); p99 = sorted(lat)[int(len(lat) * 0.99) - 1]
tps_single = sum(outs) / sum(lat)

print("\n===== 单流（batch=1，逐条）=====")
print(f"  请求数 {len(lat)}  p50 {p50*1000:.0f}ms  p99 {p99*1000:.0f}ms  均值 {st.mean(lat)*1000:.0f}ms")
print(f"  单流吞吐 {1/st.mean(lat):.2f} req/s  {tps_single:.0f} output-tok/s  (均 {st.mean(outs):.0f} tok/req)")

# ── 批吞吐 ──
print("\n===== 批吞吐（左填充并发）=====")
print(f"  {'batch':>6} | {'wall(s)':>8} | {'req/s':>7} | {'out-tok/s':>10}")
rps = {}
for B in (1, 4, 8, 16):
    ws, nt = [], []
    for rep in range(3):
        chunk = (scripts * 3)[rep * B:(rep * B) + B]
        w, n, _ = gen_batch(chunk)
        ws.append(w); nt.append(n)
    wall = sum(ws) / len(ws)
    req_s = B / wall
    tok_s = sum(nt) / sum(ws)
    rps[B] = req_s
    print(f"  {B:>6} | {wall:>8.2f} | {req_s:>7.2f} | {tok_s:>10.0f}")

vram = torch.cuda.max_memory_allocated() / 1e9
print(f"\n峰值显存 {vram:.2f} GB / 12.2 GB（单模型 bf16）")
print(f"\nRESULT_JSON {json.dumps({'p50_ms': p50*1000, 'p99_ms': p99*1000, 'single_req_s': 1/st.mean(lat), 'batch_req_s': rps, 'vram_gb': vram, 'mean_out_tok': st.mean(outs)})}")
