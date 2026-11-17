#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把流水账里每一轮请求的耗时/思考量按「思考深度」分组，回答一个问题：
低档到底有没有让模型少想、快多少？

为什么需要这个脚本：单次体验靠不住。同档位的单轮波动本身就有 ±1 秒，
「我试了一下感觉更慢」既可能是真慢，也可能只是一次噪声；而「低档被上游
静默忽略」这种情况，看界面永远看不出来——请求里带了参数，回答照旧。

用法：
    python tools/analyze_speed.py            # 自动 adb 拉取（需要连着手机，调试包）
    python tools/analyze_speed.py --dir D:\\x # 分析已经拉下来的 jsonl 目录

输出的判读规则写在最后那段提示里，别只看平均值。
"""

import argparse
import json
import os
import shutil
import statistics
import subprocess
import sys
import tempfile

PKG = "cn.wbphone.agent"
REMOTE_DIR = "files/transcripts"

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


def adb(*args, binary=False):
    return subprocess.run(
        ["adb"] + list(args),
        capture_output=True,
        text=not binary,
    )


def pull(dest):
    """把设备上的流水账拉到本地。调试包才能 run-as，正式包读不到（这是对的）。"""
    listing = adb("shell", "run-as", PKG, "ls", REMOTE_DIR)
    if listing.returncode != 0:
        print("拿不到流水账：%s" % (listing.stderr or listing.stdout).strip())
        print("可能原因：手机没连、装的是正式包（run-as 需要调试包）、或还没有任何会话。")
        return 0
    names = [n.strip() for n in listing.stdout.splitlines() if n.strip().endswith(".jsonl")]
    got = 0
    for name in names:
        raw = adb("exec-out", "run-as", PKG, "cat", REMOTE_DIR + "/" + name, binary=True).stdout
        if not raw:
            continue
        with open(os.path.join(dest, name), "wb") as f:
            f.write(raw)
        got += 1
    return got


def load(folder):
    """读出所有 turn 记录与「参数被拒」事件。坏行跳过——流水账是追加写的，进程被杀时可能留半行。"""
    turns, rejects = [], []
    for name in sorted(os.listdir(folder)):
        if not name.endswith(".jsonl"):
            continue
        path = os.path.join(folder, name)
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    o = json.loads(line)
                except Exception:
                    continue
                o["_file"] = name
                if o.get("type") == "turn":
                    turns.append(o)
                elif o.get("type") == "effort_reject":
                    rejects.append(o)
    return turns, rejects


def med(values):
    vals = [v for v in values if isinstance(v, (int, float)) and v is not None]
    return statistics.median(vals) if vals else None


def pct(values, q):
    vals = sorted(v for v in values if isinstance(v, (int, float)))
    if not vals:
        return None
    idx = min(len(vals) - 1, max(0, int(round((len(vals) - 1) * q))))
    return vals[idx]


def fmt_s(ms):
    return "—" if ms is None else "%.2fs" % (ms / 1000.0)


def fmt_n(v):
    return "—" if v is None else "%d" % round(v)


def cache_rate(t):
    hit, miss = t.get("cacheHit", -1), t.get("cacheMiss", -1)
    if not isinstance(hit, int) or not isinstance(miss, int) or hit < 0 or hit + miss <= 0:
        return None
    return hit * 100.0 / (hit + miss)


def label(effort):
    return {"low": "低", "high": "高"}.get(effort or "", "标准")


def group(turns):
    out = {}
    for t in turns:
        out.setdefault(label(t.get("effort")), []).append(t)
    return out


def describe(name, rows):
    firsts = [r.get("first") for r in rows]
    totals = [r.get("total") for r in rows]
    reason = [r.get("reasoning", 0) for r in rows]
    outs = [r.get("out") for r in rows]
    ins = [r.get("in") for r in rows]
    caches = [c for c in (cache_rate(r) for r in rows) if c is not None]
    thinking = sum(1 for r in rows if (r.get("reasoning") or 0) > 0)
    return {
        "name": name,
        "n": len(rows),
        "first": med(firsts),
        "first_lo": pct(firsts, 0.25),
        "first_hi": pct(firsts, 0.75),
        "total": med(totals),
        "reasoning": med(reason),
        "reason_share": (sum(reason) / len(rows)) if rows else None,
        "out": med(outs),
        "calls": med([r.get("calls") for r in rows]),
        "in": med(ins),
        "cache": med(caches),
        "thinking_turns": thinking,
        "models": sorted({(r.get("model") or "?") for r in rows}),
    }


def width(s):
    """终端里的显示宽度：中文按 2 格算，否则表头会错位。"""
    return sum(2 if ord(c) > 0x2E7F else 1 for c in s)


def pad(s, w, right=False):
    gap = max(0, w - width(s))
    return (" " * gap + s) if right else (s + " " * gap)


def print_table(stats):
    cols = [("档位", 8, False), ("轮次", 6, True), ("首字中位", 10, True),
            ("首字 P25~P75", 15, True), ("整轮中位", 10, True),
            ("思考字数中位", 13, True), ("输出中位", 9, True), ("调用数", 8, True),
            ("缓存命中", 9, True)]
    print("".join(pad(n, w, r) for n, w, r in cols))
    print("-" * sum(w for _, w, _ in cols))
    for s in stats:
        spread = "—" if s["first_lo"] is None else "%s~%s" % (fmt_s(s["first_lo"]), fmt_s(s["first_hi"]))
        cells = [s["name"], str(s["n"]), fmt_s(s["first"]), spread, fmt_s(s["total"]),
                 fmt_n(s["reasoning"]), fmt_n(s["out"]), fmt_n(s["calls"]),
                 "—" if s["cache"] is None else "%.0f%%" % s["cache"]]
        print("".join(pad(c, w, r) for c, (_, w, r) in zip(cells, cols)))
        models = "、".join(s["models"])
        print("    └ 模型：%s ｜ 有思考流的轮次：%d/%d ｜ 输入中位：%s tokens" % (
            models or "未知", s["thinking_turns"], s["n"], fmt_n(s["in"])))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", help="已经拉下来的 jsonl 目录（给不连手机时用）")
    args = ap.parse_args()

    tmp = None
    folder = args.dir
    if folder:
        if not os.path.isdir(folder):
            sys.exit("目录不存在：%s" % folder)
        print("分析目录：%s" % folder)
    else:
        tmp = tempfile.mkdtemp(prefix="wb-transcripts-")
        n = pull(tmp)
        if n == 0:
            shutil.rmtree(tmp, ignore_errors=True)
            sys.exit(1)
        folder = tmp
        print("已从手机拉取 %d 份流水账" % n)

    try:
        turns, rejects = load(folder)
        if not turns:
            sys.exit("没有 turn 记录。先跑一次任务再看。")

        # 只保留最近的两三份会话，避免把几周前的旧数据混进来——
        # 旧的请求体可能和现在不同（参数变过），混着算等于拿两把尺子量。
        sessions = sorted({t["_file"] for t in turns})
        if len(sessions) > 3:
            keep = set(sessions[-3:])
            turns = [t for t in turns if t["_file"] in keep]
            rejects = [r for r in rejects if r["_file"] in keep]
            print("（只算最近 %d 份会话，共 %d 轮）" % (len(keep), len(turns)))

        stats = [describe(k, v) for k, v in sorted(group(turns).items())]

        print("\n=== 按思考深度分组 ===")
        print_table(stats)

        if rejects:
            print("\n上游拒绝过这个参数 %d 次。被拒的那一轮实际走的是标准档，" % len(rejects))
            print("而且多花了一次往返（首字会明显偏大）——「选了低反而更慢」多半就是它：")
            for r in rejects[:3]:
                print("  · %s：%s" % (r.get("_file"), (r.get("body") or "")[:120]))

        # 换了模型就没法比：不同模型的思考习惯不同，差异会被算到档位头上。
        models = {m for s in stats for m in s["models"]}
        low = next((s for s in stats if s["name"] == "低"), None)
        std = next((s for s in stats if s["name"] == "标准"), None)
        if low and std:
            print("\n=== 低档 vs 标准 ===")
            for key, name in (("first", "首字"), ("total", "整轮"), ("reasoning", "思考字数")):
                a, b = std.get(key), low.get(key)
                if a and b:
                    print("  %s：标准 %s → 低 %s（%+.0f%%）" % (
                        name, fmt_n(a) if key == "reasoning" else fmt_s(a),
                        fmt_n(b) if key == "reasoning" else fmt_s(b),
                        (b - a) * 100.0 / a))
            if low["reasoning"] is not None and std["reasoning"] is not None \
                    and low["reasoning"] >= std["reasoning"] * 0.9:
                print("  → 思考字数几乎没降：这个上游多半**没认这个参数**，低档等于没设。")
            if len(models) > 1:
                print("  → 两组用了不同模型（%s），差异不能全算到档位上。" % "、".join(sorted(models)))
            if low["n"] < 8 or std["n"] < 8:
                print("  → 样本太少（低 %d 轮 / 标准 %d 轮）：单轮波动就有 ±1 秒，"
                      "现在下结论等于掷硬币。" % (low["n"], std["n"]))
            elif abs((low["first"] or 0) - (std["first"] or 0)) < 150:
                print("  → 首字差异不到 0.15 秒，落在噪声里，实际体验不会有区别。")
        elif low:
            print("\n只有低档数据，缺一组标准档对照——把设置改回标准再跑几次同样的任务。")
        else:
            print("\n还没有低档的数据：设置里选「低」，跑两次同样的任务，再回来看。")
    finally:
        if tmp:
            shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
