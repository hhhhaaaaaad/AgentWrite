// 记忆系统：短期(最近事件) + 长期(摘要)，支持遗忘与扭曲（抽象：鱼的记性差）

export interface MemoryEntry {
  id: string
  kind: 'short' | 'long'
  summary: string
  ts: number
}

export class Memory {
  private short: MemoryEntry[] = []
  private long: MemoryEntry[] = []

  add(entry: MemoryEntry): void {
    if (entry.kind === 'short') this.short.push(entry)
    else this.long.push(entry)
  }

  recent(limit = 20): MemoryEntry[] {
    return this.short.slice(-limit)
  }

  all(): MemoryEntry[] {
    return [...this.long, ...this.short]
  }

  /**
   * TODO(P1): 「睡一觉」——把短期记忆压缩成长期摘要，模拟遗忘/扭曲。
   */
  compact(): void {
    // 合并 short → 一条 long 摘要
  }
}
