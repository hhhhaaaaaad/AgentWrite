import type { DataConnector } from './index'

// Mock 连接器：返回样本账单/订阅数据。
// TODO(P1): 实现 ImapConnector（邮箱订阅扫描）、CsvConnector（账单流水）、OcrConnector（截图）。
export class MockConnector implements DataConnector {
  readonly name = 'mock'

  async ingest(input?: unknown): Promise<unknown> {
    return input ?? { sample: true, message: 'mock connector: 无真实数据源' }
  }
}
