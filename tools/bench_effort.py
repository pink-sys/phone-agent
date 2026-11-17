#!/usr/bin/env python3
"""思考深度对照测试：经手机上的本地网关直连上游，比较三档的思考量与耗时。

为什么走本地网关而不是直接在电脑上请求：登录令牌用 Android Keystore 加密存在手机上，
电脑解不开。`adb forward` 把手机回环端口映射过来，请求仍然从手机进程发出、用手机上的令牌，
等于测的就是 App 走的同一条链路。

前置：
  1. 手机连上，App 已登录，设置里「本地网关」为开；
  2. adb forward tcp:8787 tcp:8787

用法：
  python tools/bench_effort.py                 # 低/标准/高 各 3 次，交错跑
  python tools/bench_effort.py --rounds 5      # 各 5 次（更稳，也慢得多）
  python tools/bench_effort.py --port 8788     # 端口顺延过的话自己指定

判断标准（别用「你好啊」那种题——三档都只思考 200 字，落在下限上，测不出差别）：
  - 低档的思考 tokens 中位数明显低于标准 → 参数生效；
  - 高档若和标准重叠 → 高档没有额外增益，别在界面暗示它「想得更细」。
"""

import argparse
import json
import statistics
import time
import urllib.request

# 故意选一道要想很久的题：思考空间大，档位差异才看得出来。
PROMPT = "解释为什么分布式系统里 CAP 三者不能兼得，并举一个真实系统说明它放弃了哪一个。"


def one_call(url, effort, prompt):
    body = {"model": "hy3", "stream": True,
            "messages": [{"role": "user", "content": prompt}]}
    if effort:
        body["reasoning_effort"] = effort
    req = urllib.request.Request(url, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    started = time.time()
    first = None
    usage = None
    chars = 0
    err = ""
    with urllib.request.urlopen(req, timeout=240) as resp:
        for raw in resp:
            line = raw.decode("utf-8", "replace").strip()
            if not line.startswith("data:"):
                continue
            payload = line[5:].strip()
            if payload == "[DONE]":
                break
            try:
                obj = json.loads(payload)
            except Exception:
                continue
            if obj.get("error"):
                err = str(obj["error"])[:200]
            if obj.get("usage"):
                usage = obj["usage"]
            choice = (obj.get("choices") or [{}])[0]
            delta = choice.get("delta") or (choice.get("message") or {})
            piece = delta.get("reasoning_content") or ""
            if piece and first is None:
                first = time.time() - started
            chars += len(piece)
    usage = usage or {}
    return {
        "effort": effort or "标准",
        "reason": usage.get("completion_tokens_details", {}).get("reasoning_tokens", -1),
        "completion": usage.get("completion_tokens", -1),
        "sec": round(time.time() - started, 2),
        "first": round(first, 2) if first else None,
        "chars": chars,
        "err": err,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8787)
    ap.add_argument("--rounds", type=int, default=3)
    ap.add_argument("--prompt", default=PROMPT)
    args = ap.parse_args()
    url = "http://127.0.0.1:%d/v1/chat/completions" % args.port

    # 交错跑：时间漂移（网络、上游排队）平均分给三档，而不是全压在一档上。
    plan = []
    for _ in range(args.rounds):
        plan += ["low", None, "high"]

    rows = []
    for effort in plan:
        row = one_call(url, effort, args.prompt)
        rows.append(row)
        print(json.dumps(row, ensure_ascii=False), flush=True)

    print("\n档位    次数  思考token(中位)  区间          整轮秒(中位)  首字秒(中位)")
    for name in ["low", "标准", "high"]:
        group = [r for r in rows if r["effort"] == name and r["reason"] > 0]
        if not group:
            continue
        reasons = sorted(r["reason"] for r in group)
        print("%-6s %4d %13d  %4d~%-6d %12.1f %13s" % (
            name, len(group), statistics.median(reasons), reasons[0], reasons[-1],
            statistics.median([r["sec"] for r in group]),
            statistics.median([r["first"] for r in group if r["first"]])))

    base = [r["reason"] for r in rows if r["effort"] == "标准" and r["reason"] > 0]
    low = [r["reason"] for r in rows if r["effort"] == "low" and r["reason"] > 0]
    if base and low:
        drop = 1 - statistics.median(low) / statistics.median(base)
        print("\n低档相对标准：思考量 %+.0f%%%s" % (
            -drop * 100, "（参数生效）" if drop > 0.3 else "（几乎没变，参数多半被忽略）"))


if __name__ == "__main__":
    main()
