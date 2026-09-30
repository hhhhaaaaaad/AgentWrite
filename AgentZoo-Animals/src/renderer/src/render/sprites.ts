// 动物精灵：emoji/简单位图起步，后续替换真立绘。
// TODO(P0): 走动动画 = 平移 + 轻微颠簸 + 朝向翻转；状态映射到不同动作。

export interface SpriteState {
  animalId: string
  emoji: string
  position: { x: number; y: number }
  facing: 'left' | 'right'
  /** 对应主进程的 AnimalState */
  state: 'idle' | 'moving' | 'working' | 'socializing' | 'done'
  /** 头顶气泡文字 */
  bubble?: string
}

export const SPECIES_EMOJI: Record<string, string> = {
  fox: '🦊',
  parrot: '🦜',
  cat: '🐱'
}
