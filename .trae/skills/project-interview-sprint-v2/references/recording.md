# Compact Recording

Use only when the user requests Obsidian notes or cross-session continuation.

## Canonical Files

Maintain:

```text
<Sprint>/
  00-当前学习状态.md
  知识卡/
  项目事实表.md          # project mode only
  每日复盘/             # only when requested
  面试话术/             # interview output only
```

`00-当前学习状态.md` is the resume entry point. Read it before historical notes.

## Current State

Keep it short:

```markdown
# 当前学习状态

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
```

## Deduplication

- Knowledge cards are the canonical explanation.
- Daily reviews link to cards and record progress, not full answers.
- Record only new knowledge, corrected mistakes, final standard answers, evidence, and changed project facts.
- Update after a coherent block, not after every message.
- Do not reload all daily reviews to resume a sprint.

Ask for filesystem permission when required. If writing is unavailable, stage Markdown and clearly mark it unsynced.
