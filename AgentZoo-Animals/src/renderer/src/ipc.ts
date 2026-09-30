// 渲染进程侧 IPC 类型定义，与 src/preload/index.ts 保持一致

export interface ZooApi {
  zoo: {
    // TODO(P0): 实现后在此补全签名
    // getState(): Promise<ZooSnapshot>
    // feed(payload: unknown): Promise<void>
  }
}

declare global {
  interface Window {
    zooApi?: ZooApi
  }
}

export {}
