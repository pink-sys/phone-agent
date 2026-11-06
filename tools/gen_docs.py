#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 ModelRegistry.kt 里的模型清单生成 Markdown 表格，填进 README.md。

为什么要有这个脚本：README 里手写过一份倍率，结果模型清单一改文档就过期。
现在唯一的事实来源是 ModelRegistry.kt，改了模型/倍率后重跑一次：

    python tools/gen_docs.py

脚本只动 README.md 里这两个标记之间的内容，其他部分一个字不碰：

    <!-- model-table:start -->
    <!-- model-table:end -->

倍率的最终解释权在服务端（扣费看返回的 usage），表格只是展示，所以表尾固定
带一句「以 App 设置页为准」。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REGISTRY = os.path.join(ROOT, "app", "src", "main", "java", "cn", "wbphone", "agent", "ModelRegistry.kt")
README = os.path.join(ROOT, "README.md")

START = "<!-- model-table:start -->"
END = "<!-- model-table:end -->"


def die(msg):
    print("[失败] " + msg, file=sys.stderr)
    sys.exit(1)


def parse_registry():
    src = open(REGISTRY, encoding="utf-8").read()
    rows = re.findall(r'Option\("([^"]+)",\s*"([^"]+)",\s*"([^"]*)",\s*"([^"]*)"\)', src)
    if not rows:
        die("没从 ModelRegistry.kt 里解析出任何 Option(...)，正则和源码对不上了？")
    m = re.search(r'const val DEFAULT_ID = "([^"]+)"', src)
    if not m:
        die("没找到 DEFAULT_ID")
    return rows, m.group(1)


def render(rows, default_id):
    out = [
        "| 档位 | `model` 字段 | 倍率 | 说明 |",
        "| --- | --- | --- | --- |",
    ]
    for mid, label, credit, note in rows:
        name = label + ("（默认）" if mid == default_id else "")
        shown = "免费" if credit == "x0.00" else (credit or "—")
        out.append("| %s | `%s` | %s | %s |" % (name, mid, shown, note))
    out.append("")
    out.append("> 本表由 `tools/gen_docs.py` 从 `ModelRegistry.kt` 生成，别手改；")
    out.append("> 改了模型清单就重跑一次脚本。倍率以 App 设置页实时显示为准，")
    out.append("> 真实扣费以服务端返回的 `usage` 为准。")
    return "\n".join(out)


def main():
    rows, default_id = parse_registry()
    table = render(rows, default_id)

    readme = open(README, encoding="utf-8").read()
    pattern = re.compile(re.escape(START) + r".*?" + re.escape(END), re.S)
    if not pattern.search(readme):
        die("README.md 里找不到 %s … %s 标记对" % (START, END))
    readme = pattern.sub(START + "\n" + table + "\n" + END, readme, count=1)
    open(README, "w", encoding="utf-8", newline="").write(readme)
    print("已写入 README.md：%d 个模型（默认 %s）" % (len(rows), default_id))


if __name__ == "__main__":
    main()
