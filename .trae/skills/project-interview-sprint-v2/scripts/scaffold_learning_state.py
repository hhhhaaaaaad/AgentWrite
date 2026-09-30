#!/usr/bin/env python3
"""Create a minimal, non-destructive learning-state structure."""

from __future__ import annotations

import argparse
from pathlib import Path


STATE_TEMPLATE = """# 当前学习状态

## 配置
- 目标：
- 时间档：
- 实践深度：
- 回答详细度：
- 理论/实践/表达评分：

## 已掌握

## 薄弱点

## 项目事实变化

## 已产出证据

## 待办

## 下一题

## 最近同步时间
"""


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("vault", help="Obsidian vault or target directory")
    parser.add_argument("sprint", help="Sprint directory name")
    args = parser.parse_args()

    root = Path(args.vault).expanduser().resolve() / args.sprint
    root.mkdir(parents=True, exist_ok=True)
    (root / "知识卡").mkdir(exist_ok=True)

    state = root / "00-当前学习状态.md"
    if not state.exists():
        state.write_text(STATE_TEMPLATE, encoding="utf-8")
        print(f"created: {state}")
    else:
        print(f"kept: {state}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
