#!/usr/bin/env python3
"""把 config.local.yaml(真 key 覆盖层, gitignore)精确叠加到 config.yaml(模板, git)上，输出完整 runtime 配置。

背景：workhorse-agent(go)的 internal/config/load.go 只合并 `Default() → 单个 --config yaml →
WORKHORSE_AGENT_* env → CLI flag`，**不合并同目录 config.local.yaml、不认裸 ANTHROPIC_* env、env 无 model 覆盖项**。
所以换 provider / 注入真 key 必须落到那唯一一个 --config 文件里。本脚本就做这件事。

实现：不重排 config.yaml，只**就地替换被 config.local 覆盖到的标量叶子**——保留模板里的注释、列表
(如 tools.default_allowed_tools)、字段顺序与格式。仅依赖 Python 标准库(部署机无 pyyaml/yq)。

用法：merge-config.py config.yaml config.local.yaml > config.runtime.yaml
"""
import re
import sys

KEY_RE = re.compile(r"^(\s*)([A-Za-z0-9_.\-]+):\s*(.*?)\s*$")


def scalar_overrides(path):
    """把覆盖文件解析成 {点分路径: 原始标量串}，只收标量叶子(忽略 map 头与列表项)。"""
    overrides = {}
    stack = []  # [(indent, key)]
    for raw in open(path, encoding="utf-8"):
        line = raw.rstrip("\n")
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        m = KEY_RE.match(line)
        if not m:
            continue  # 列表项等，覆盖层不应有，跳过
        indent, key, value = len(m.group(1)), m.group(2), m.group(3)
        while stack and stack[-1][0] >= indent:
            stack.pop()
        dotted = ".".join(k for _, k in stack) + ("." if stack else "") + key
        if value != "" and not value.startswith("#"):
            overrides[dotted] = value
        else:
            stack.append((indent, key))  # map 头，入栈做父级
    return overrides


def patch(base_path, overrides):
    """流式读 base，按缩进维护路径栈；命中覆盖路径的标量叶子则替换其值，其余原样输出。"""
    out = []
    stack = []  # [(indent, key)]
    for raw in open(base_path, encoding="utf-8"):
        line = raw.rstrip("\n")
        m = KEY_RE.match(line)
        if not m or line.lstrip().startswith("#"):
            out.append(line)
            continue
        indent, key, value = len(m.group(1)), m.group(2), m.group(3)
        is_scalar = value != "" and not value.startswith("#")
        while stack and stack[-1][0] >= indent:
            stack.pop()
        dotted = ".".join(k for _, k in stack) + ("." if stack else "") + key
        if is_scalar and dotted in overrides:
            # 保留行内注释(若有)
            comment = ""
            hm = re.search(r"\s+#.*$", line)
            if hm:
                comment = hm.group(0)
            out.append(" " * indent + key + ": " + overrides[dotted] + comment)
        else:
            out.append(line)
        if not is_scalar:
            stack.append((indent, key))
    return "\n".join(out) + "\n"


def main():
    if len(sys.argv) != 3:
        sys.exit("用法: merge-config.py <base config.yaml> <overlay config.local.yaml>")
    sys.stdout.write(patch(sys.argv[1], scalar_overrides(sys.argv[2])))


if __name__ == "__main__":
    main()
