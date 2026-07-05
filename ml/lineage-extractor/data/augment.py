"""041-R 交叉生成：M1 改写脚本外形，label 由骨架已知(免标)。丢弃改丢表名的结果。"""
import re

_PROMPT = ("Rewrite the following ETL script to look more realistic (rename variables, "
           "restructure), but you MUST keep every table name literal unchanged. "
           "Output ONLY the rewritten script.\n\n{content}")


def _rewrite(content: str) -> str:
    # M1 裸文本改写：LlmClient.extract 返回血缘 dict、不出裸文本，故此处直连 openai backend
    from openai import OpenAI
    import os
    cli = OpenAI(api_key=os.environ["DASHSCOPE_API_KEY"],
                 base_url=os.environ.get("DASHSCOPE_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"))
    r = cli.chat.completions.create(model=os.environ.get("QWEN_MODEL", "qwen-max"),
          temperature=0.7, timeout=60,
          messages=[{"role": "user", "content": _PROMPT.format(content=content)}])
    return r.choices[0].message.content or ""


def paraphrase_script(content: str, tables_keep: set[str]) -> str | None:
    out = _rewrite(content)
    if all(re.search(rf'(?<![\w.]){re.escape(t)}(?![\w])', out, re.IGNORECASE) for t in tables_keep):
        return out
    return None
