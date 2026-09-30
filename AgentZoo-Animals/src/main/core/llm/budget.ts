// Token 预算管理器（粮仓）：每日预算 + 每动物配额，超了自动降级省电

export class TokenBudget {
  private used = new Map<string, number>()

  constructor(private readonly dailyLimit: number) {}

  /** 记录某动物消耗的 token。TODO(P0): 持久化每日用量。 */
  consume(animalId: string, tokens: number): void {
    this.used.set(animalId, (this.used.get(animalId) ?? 0) + tokens)
  }

  usedBy(animalId: string): number {
    return this.used.get(animalId) ?? 0
  }

  totalUsed(): number {
    return [...this.used.values()].reduce((a, b) => a + b, 0)
  }

  /** 是否进入省电模式（少说话、走模板）。TODO(P0) */
  isThrottled(animalId: string): boolean {
    void animalId
    return this.totalUsed() >= this.dailyLimit
  }
}
