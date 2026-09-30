// 工具注册表：每只动物声明自己的工具集，引擎按名字查找、调用

export interface Tool {
  name: string
  description: string
  run(args: unknown): Promise<unknown>
}

const registry = new Map<string, Tool>()

export function registerTool(tool: Tool): void {
  registry.set(tool.name, tool)
}

export function getTool(name: string): Tool | undefined {
  return registry.get(name)
}

export function listTools(): Tool[] {
  return [...registry.values()]
}
