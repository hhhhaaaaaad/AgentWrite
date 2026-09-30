// LLM Provider 抽象：Mock → DeepSeek / OpenAI 兼容 / Ollama

export type ProviderName = 'mock' | 'deepseek' | 'openai' | 'ollama'

export interface LLMOptions {
  temperature?: number
  maxTokens?: number
  model?: string
}

export interface LLMProvider {
  readonly name: ProviderName
  complete(prompt: string, opts?: LLMOptions): Promise<string>
}
