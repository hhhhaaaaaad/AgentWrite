// 账本狐狸任务 pipeline：ingest → parse → classify → detect → compute → report

export interface Subscription {
  name: string
  amount: number
  currency: string
  cycle: 'monthly' | 'yearly' | 'unknown'
  nextCharge?: string
  confidence: number // 0-1，低置信度需人工确认
  cancellable: boolean
}

export interface BillReport {
  subscriptions: Subscription[]
  totalMonthlyCost: number
  saveableMonthly: number
  summary: string
}

/**
 * 真实任务链：读账单 → 解析 → 分类 → 识别订阅/异常 → 计算可省金额 → 出报告。
 * TODO(P0): 接入 connectors（mock → IMAP/CSV）+ LLM provider，当前为桩。
 */
export async function billAnalysisPipeline(input: unknown): Promise<BillReport> {
  void input
  return {
    subscriptions: [],
    totalMonthlyCost: 0,
    saveableMonthly: 0,
    summary: '[桩] 账本狐狸尚未接入账单数据'
  }
}
