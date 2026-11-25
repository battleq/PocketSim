#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""llm_stats.jsonl 分析脚本（O-07 实测确认用，也可通用于其他通道）。

数据来源：debug 包运行时写到应用私有目录的 llm_stats.jsonl，拉取方式：
    adb exec-out run-as com.pocketsim.app cat files/llm_stats.jsonl > llm_stats.jsonl

用法：
    python llm_stats_report.py llm_stats.jsonl                # 单文件：按 tag 分组汇总
    python llm_stats_report.py o07.jsonl --baseline base.jsonl # A/B 对比：优化通道 token/耗时/回退率

指标口径（每行字段见 LlmClient.kt llmUsageJsonLine）：
    ms=端到端毫秒  usage.prompt/completion/reasoning/cached=token  ok/retried=成败与限流重试
O-07 判据参考：completion(patch 组) ≤ completion(baseline 完整模型组) 的 40%；
optimize/full 行数 / optimize/patch 行数 = 回退重发率（应显著低于 1）。
"""
import argparse
import json
import sys
from collections import defaultdict


def load(path):
    rows = []
    with open(path, encoding="utf-8-sig") as f:  # 容忍 Windows 工具写入的 BOM，避免丢首行
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:
                print(f"[warn] 跳过坏行: {line[:80]}", file=sys.stderr)
    return rows


def avg(vals):
    return sum(vals) / len(vals) if vals else 0.0


def summarize(rows):
    """按 tag 分组汇总：次数、平均 ms、平均 token、ok/retried 计数。"""
    groups = defaultdict(list)
    for r in rows:
        groups[r.get("tag") or "(untagged)"].append(r)
    out = {}
    for tag, rs in sorted(groups.items()):
        ok = [r for r in rs if r.get("ok")]
        out[tag] = {
            "n": len(rs),
            "ok": len(ok),
            "retried": sum(1 for r in rs if r.get("retried")),
            "ms": avg([r.get("ms", 0) for r in ok]),
            "prompt": avg([(r.get("usage") or {}).get("prompt", 0) for r in ok]),
            "completion": avg([(r.get("usage") or {}).get("completion", 0) for r in ok]),
            "reasoning": avg([(r.get("usage") or {}).get("reasoning", 0) for r in ok]),
            "model": ",".join(sorted({r.get("model", "?") for r in rs})),
        }
    return out


def print_table(title, stat):
    print(f"\n== {title} ==")
    hdr = f"{'tag':<18}{'n':>4}{'ok':>4}{'retry':>6}{'ms':>9}{'prompt':>9}{'compl':>8}{'reason':>8}  model"
    print(hdr)
    print("-" * len(hdr))
    for tag, s in stat.items():
        print(f"{tag:<18}{s['n']:>4}{s['ok']:>4}{s['retried']:>6}"
              f"{s['ms']:>9.0f}{s['prompt']:>9.0f}{s['completion']:>8.0f}"
              f"{s['reasoning']:>8.0f}  {s['model']}")


def optimize_focus(stat, label):
    """提取优化通道关键指标并打印 O-07 相关结论行。"""
    patch = stat.get("optimize/patch")
    full = stat.get("optimize/full")
    legacy = stat.get("optimize")
    comp = (patch or legacy or {}).get("completion")
    if comp is None:
        return None
    line = f"[{label}] 优化通道平均输出 token={comp:.0f}（组：{'optimize/patch' if patch else 'optimize'}）"
    if patch:
        n_patch, n_full = patch["n"], (full or {"n": 0})["n"]
        line += f"；回退重发率={n_full / max(n_patch, 1) * 100:.0f}%"
    if legacy and patch:
        line += f"；同文件完整模型组 token={legacy['completion']:.0f}"
    print(line)
    return comp


def main():
    ap = argparse.ArgumentParser(description="llm_stats.jsonl 分组汇总与 A/B 对比")
    ap.add_argument("file", help="llm_stats.jsonl 路径")
    ap.add_argument("--baseline", help="基线 jsonl（如 O-07 前的 v1.2.42 数据），用于对比优化通道")
    ap.add_argument("--tag", default=None, help="只看指定 tag（默认全部）")
    args = ap.parse_args()

    rows = load(args.file)
    if not rows:
        sys.exit("文件为空或全部坏行")
    stat = summarize(rows)
    if args.tag:
        stat = {k: v for k, v in stat.items() if k == args.tag}
    print_table(args.file, stat)
    cur = optimize_focus(stat, "当前")

    if args.baseline:
        base = summarize(load(args.baseline))
        print_table(args.baseline, base)
        b = optimize_focus(base, "基线")
        if b and cur is not None:
            ratio = cur / b * 100 if b else float("inf")
            verdict = "PASS" if ratio <= 40 else "未达标"
            print(f"\n== O-07 判据：patch 输出 token / 完整模型 = {ratio:.0f}%（要求 ≤40%）→ {verdict} ==")
            bm = avg([v["ms"] for k, v in base.items() if k.startswith("optimize")])
            cm = avg([v["ms"] for k, v in stat.items() if k.startswith("optimize")])
            if bm:
                print(f"    优化通道平均耗时：基线 {bm:.0f}ms → 当前 {cm:.0f}ms（降 {(1 - cm / bm) * 100:.0f}%）")


if __name__ == "__main__":
    main()
