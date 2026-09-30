import type { LLMProvider, LLMOptions, ProviderName } from './provider'

// Mock Provider：P0 用规则/模板返回假结果，不消耗 token。
export class MockProvider implements LLMProvider {
  readonly name: ProviderName = 'mock'

  async complete(prompt: string, opts?: LLMOptions): Promise<string> {
    void opts
    // TODO(P0): 按任务类型返回桩结果（账单分析 / 文案生成 / 闲聊各一版）
    return `[mock] ${prompt.slice(0, 40)}...`
  }
}
