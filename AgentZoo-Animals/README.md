# 赛博动物园 · Agent Zoo Animals

一个**桌面端多智能体动物园**：每只「动物」都是一个负责真实任务的 AI Agent，住在一个俯视 2D 动物园里，会自己走动、干活、串门、吐槽。**任务为主，社交为皮，抽象当道。**

> 完整技术方案见 [`docs/PLAN.md`](docs/PLAN.md)。

## 当前状态

- 阶段：**P0 骨架** —— 框架 + 方案已就位，业务逻辑为桩（stub）实现，按 PLAN.md 分期逐步填充。
- MVP 三只动物：🦊 账本狐狸（消费体检）· 🦜 嘴替鹦鹉（文案嘴替）· 🐱 摸鱼猫（氛围位）。

## 技术栈

| 层 | 选型 |
|---|---|
| 壳 | Electron + electron-vite |
| 前端 | React 18 + Vite + TypeScript |
| 可视化 | Canvas 2D + requestAnimationFrame（俯视地图，可换 PixiJS） |
| Agent 引擎 | 手写状态机（每只动物一个 loop） |
| LLM | 抽象 Provider：Mock → DeepSeek / OpenAI 兼容 / Ollama |
| 存储 | 先 JSON 文件存储，后续换 SQLite |
| 连接器 | 抽象接口：Mock → IMAP / CSV / OCR |

## 目录结构

```
src/
├─ main/                 # Electron 主进程
│  ├─ index.ts           # 窗口/托盘/通知/生命周期
│  └─ core/              # Agent 引擎（纯 TS，可独立测试）
│     ├─ zoo.ts          # 动物园管理器 + tick 调度
│     ├─ animals/        # 每只动物的状态机 + 人设
│     ├─ tasks/          # 真实任务 pipeline（账单分析 / 文案生成）
│     ├─ tools/          # 工具注册表
│     ├─ memory/         # 短期 + 长期记忆
│     ├─ llm/            # provider 抽象 + mock + token 预算
│     ├─ connectors/     # 数据源连接器（mock/imap/csv）
│     └─ store/          # 持久化
├─ preload/              # contextBridge 安全桥
└─ renderer/             # 渲染进程
   ├─ index.html
   └─ src/
      ├─ App.tsx
      ├─ render/         # Canvas 游戏循环 + 地图 + 精灵
      └─ ui/             # 面板 / 档案卡 / 设置
```

## 运行

```bash
npm install
npm run dev
```

## 说明

- 本目录是项目**框架骨架**；`core/` 下是 Agent 引擎的接口与类型定义，逻辑随分期逐步实现。
- 所有 `TODO(...)` 标记处是待实现点，对应 `docs/PLAN.md` 的分期路线。
