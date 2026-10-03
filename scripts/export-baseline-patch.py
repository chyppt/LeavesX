"""从明确保存的源码基线生成补丁，不触碰开发者的 Git 索引和未提交改动。"""

import argparse
import difflib
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--subject", required=True)
    parser.add_argument("paths", nargs="+")
    args = parser.parse_args()
    baseline = args.baseline.resolve(strict=True)
    source = args.source.resolve(strict=True)
    if args.output.exists():
        raise SystemExit("补丁文件已存在，请检查后使用新的输出路径，避免覆盖已有成果。")
    chunks = ["From 0000000000000000000000000000000000000000 Mon Sep 17 00:00:00 2001\n",
              "From: chyppt <pptwxo@163.com>\n",
              "Date: Sat, 3 Oct 2026 01:00:00 +0800\n",
              f"Subject: [PATCH] {args.subject}\n\n"]
    changed = 0
    for name in args.paths:
        before = (baseline / name).resolve(strict=True)
        after = (source / name).resolve(strict=True)
        if not before.is_relative_to(baseline) or not after.is_relative_to(source):
            raise SystemExit("源码路径不能越过已指定的目录。")
        # 统一行尾；Git 补丁重放由调用者另外验证。
        old = before.read_text(encoding="utf-8").splitlines(keepends=True)
        new = after.read_text(encoding="utf-8").splitlines(keepends=True)
        relative = Path(name).as_posix()
        delta = list(difflib.unified_diff(old, new, fromfile=f"a/{relative}", tofile=f"b/{relative}"))
        if delta:
            chunks.append(f"diff --git a/{relative} b/{relative}\n")
            chunks.extend(delta)
            changed += 1
    if not changed:
        raise SystemExit("指定文件没有变化。")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8", newline="\n") as stream:
        stream.write("".join(chunks))
    print(f"已导出 {changed} 个文件：{args.output}")


if __name__ == "__main__":
    main()
