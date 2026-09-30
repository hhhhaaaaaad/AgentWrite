# Open Questions（跨方案追踪）

## ralplan-评测平台-生产级 - 2026-09-28
- [ ] ε 取值（默认 0.01）在生产级是否需要更严格（如 0.005）— 影响「可复现」AC#6 的判定阈值。
- [ ] 前端轮询 vs WebSocket 推送 — 轮询简单、推送实时，前者优先，后者增量。
- [ ] `result_backend` 用 Postgres 还是 Redis — 前者持久、后者低延迟，影响结果查询路径。
- [ ] LLM-judge 的 JEV 具体接入方式（API 地址 / 鉴权 / 计费）— 影响成对胜率 Phase 3 落地。
- [ ] 多环境隔离命名空间约定（staging/prod eval 的 DB/collection 命名规范）— 影响存储隔离 A 的配置驱动实现。
- [ ] 反哺回归 diff 的「超阈值告警」具体阈值 — 影响回归 gate 的灵敏度。
- [ ] `hnsw_ef` 缺省值与线上一致性取值 — 影响 fidelity 模式真实性，需与线上 Qdrant 配置对齐。
