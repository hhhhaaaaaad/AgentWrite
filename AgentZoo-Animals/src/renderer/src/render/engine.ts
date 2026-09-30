// Canvas 渲染主循环（游戏循环）。
// TODO(P0): 从主进程拉取 ZooSnapshot，驱动地图/动物精灵/气泡渲染。

export function startRenderLoop(canvas: HTMLCanvasElement): () => void {
  const ctx = canvas.getContext('2d')
  if (!ctx) return () => {}

  let raf = 0
  let last = performance.now()

  const frame = (now: number): void => {
    const _dt = (now - last) / 1000
    last = now

    // 清屏
    ctx.fillStyle = '#0f1115'
    ctx.fillRect(0, 0, canvas.width, canvas.height)

    // TODO(P0): drawMap(ctx) → drawAnimals(ctx) → drawBubbles(ctx)
    drawPlaceholder(ctx, now)

    raf = requestAnimationFrame(frame)
  }

  raf = requestAnimationFrame(frame)
  return () => cancelAnimationFrame(raf)
}

function drawPlaceholder(ctx: CanvasRenderingContext2D, now: number): void {
  ctx.fillStyle = '#3a3f4b'
  ctx.font = '18px sans-serif'
  ctx.textAlign = 'center'
  const x = ctx.canvas.width / 2
  const y = ctx.canvas.height / 2 + Math.sin(now / 400) * 8
  ctx.fillText('🐾 动物园渲染引擎占位 · 待接入动物精灵', x, y)
}
