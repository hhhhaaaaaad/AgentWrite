import { contextBridge, ipcRenderer } from 'electron'

// 暴露给渲染进程的类型安全 API（与 src/renderer/src/ipc.ts 的 ZooApi 保持一致）
const api = {
  zoo: {
    // TODO(P0): 定义并实现 IPC 通道
    // getState: () => ipcRenderer.invoke('zoo:getState'),
    // feed: (payload) => ipcRenderer.invoke('zoo:feed', payload),
    // onEvent: (cb) => { const h = (_e, data) => cb(data); ipcRenderer.on('zoo:event', h); return () => ipcRenderer.removeListener('zoo:event', h) }
  }
}

contextBridge.exposeInMainWorld('zooApi', api)

export type ZooApi = typeof api
