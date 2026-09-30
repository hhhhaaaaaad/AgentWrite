# P0-6 IDOR 修复（worker-security）

## 已完成
- `MemoryController.detail(id)`：新增 `getCurrentUserId()` 校验，`record == null || !userId.equals(record.getUserId())` 统一返回「记忆不存在或无权访问」（不区分存在性，防枚举）。
- `MemoryController.delete(id)`：先 `memoryManager.get(id)` 取 record 做归属校验，通过后才 `memoryManager.delete(id)`。
- `SecurityConfig`：`/api/v1/memory/migrate/all` 由 `permitAll()` 改为 `authenticated()`，附注释说明后续可换 `hasRole("ADMIN")` 或迁运维脚本。
- 新增 `domain/.../model/exception/MemoryAccessDeniedException`（本轮未接线，供后续 `MemoryManager.delete(userId, memoryId)` 重载使用）。

## 接口约束（后续接线注意）
- Controller 现走 `memoryManager.get(id)` 再判归属，未使用 `delete(userId, memoryId)` 重载；P1 接线后建议直接调用重载，避免 TOCTOU。
- 编译：`mvn -pl sutone-agent-bok-trigger -am compile -DskipTests` → BUILD SUCCESS。
