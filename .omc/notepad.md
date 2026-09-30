# Notepad
<!-- Auto-managed by OMC. Manual edits preserved in MANUAL section. -->

## Priority Context
<!-- ALWAYS loaded. Keep under 500 chars. Critical discoveries only. -->

## Working Memory
<!-- Session notes. Auto-pruned after 7 days. -->
### 2026-09-22 14:21
P0-1~P0-5 写路径主干实现中。已确认：MemoryExtractorTest/MemoryRetrieverTest 仅 mock search，不依赖被删的 insert/update/nextId。MemoryRecordEntity.create 签名不变。
### 2026-09-22 14:33
P0-1~P0-5 完成。学习点：Mockito mock 返回 Long 的方法默认可能返回 0L（非 null），导致 findUpdateTarget 走 UPDATE 路径，测试需显式 stub 返回 null。MemoryEvaluationTest 因本地无 Qdrant(localhost:6333) 连接失败，属环境问题非本次改动。


## 2026-09-22 14:21
P0-1~P0-5 写路径主干实现中。已确认：MemoryExtractorTest/MemoryRetrieverTest 仅 mock search，不依赖被删的 insert/update/nextId。MemoryRecordEntity.create 签名不变。


## MANUAL
<!-- User content. Never auto-pruned. -->

