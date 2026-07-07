"""052 Foundational: content-hash 工具（去重 + train∩test 污染检测）。

data-model 实体 1/4、SC-006：训练语料/银标与测试金标 A∪B 的 content-hash 必须零重叠。
空白规范化使"同脚本不同缩进/换行"仍判为同一份（防脱敏/格式漂移漏检污染）。纯函数、无第三方依赖。
"""
from __future__ import annotations

import hashlib
import re

_WS = re.compile(r"\s+")


def normalize(content: str | None) -> str:
    """规范化脚本内容用于 hash：统一换行 → 压缩连续空白为单空格 → 去首尾空白。"""
    if not content:
        return ""
    s = content.replace("\r\n", "\n").replace("\r", "\n")
    return _WS.sub(" ", s).strip()


def content_hash(content: str | None) -> str:
    return hashlib.sha256(normalize(content).encode("utf-8")).hexdigest()


def hash_set(contents) -> set[str]:
    return {content_hash(c) for c in contents}


def overlap(a_contents, b_contents) -> set[str]:
    """两组内容的 content-hash 交集（污染检测：期望为空集）。"""
    return hash_set(a_contents) & hash_set(b_contents)


def dedup(items, key=lambda x: x):
    """按 content-hash 去重，保留首现。key 从元素提取内容字符串。"""
    seen: set[str] = set()
    out = []
    for it in items:
        h = content_hash(key(it))
        if h not in seen:
            seen.add(h)
            out.append(it)
    return out
