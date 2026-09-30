import { useEffect, useRef } from 'react'
import { startRenderLoop } from './render/engine'

export default function App() {
  const canvasRef = useRef<HTMLCanvasElement>(null)

  useEffect(() => {
    if (!canvasRef.current) return
    return startRenderLoop(canvasRef.current)
  }, [])

  return (
    <div className="zoo-app">
      <header className="zoo-header">
        <h1>🦊 赛博动物园</h1>
        <span className="zoo-status">P0 骨架 · 待接入 Agent 引擎</span>
      </header>
      <canvas ref={canvasRef} className="zoo-canvas" width={1280} height={720} />
    </div>
  )
}
