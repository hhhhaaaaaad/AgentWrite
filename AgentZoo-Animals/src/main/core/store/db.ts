// 持久化：P0 用 JSON 文件，P1 换 SQLite（接口不变）。
// TODO(P0): 实现 createJsonStore（node:fs 读写）；P1 换 better-sqlite3。

export interface Store {
  get<T>(key: string): Promise<T | undefined>
  set(key: string, value: unknown): Promise<void>
}

export function createJsonStore(_filePath: string): Store {
  // TODO(P0): 读写本地 JSON 文件
  const map = new Map<string, unknown>()
  return {
    async get<T>(key: string): Promise<T | undefined> {
      return map.get(key) as T | undefined
    },
    async set(key: string, value: unknown): Promise<void> {
      map.set(key, value)
    }
  }
}
