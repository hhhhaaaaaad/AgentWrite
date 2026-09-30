import { Animal, type AnimalProfile, type PerceivedEvent } from './base'

// 嘴替鹦鹉：文案嘴替 Agent。真实任务 = copywritingPipeline（素材 → 风格 → 生成）。
export class Parrot extends Animal {
  perceive(events: PerceivedEvent[]): void {
    // TODO(P0): 关心「需要代笔/吐槽」类事件；联动：接狐狸的报告做二次创作
    void events
  }

  async plan(): Promise<void> {
    // TODO(P0): 有素材 → working
  }

  async act(): Promise<void> {
    // TODO(P0): working → 调用 copywritingPipeline 生成发疯文学/阴阳怪气文案
  }

  async reflect(): Promise<void> {
    // TODO(P1): 记录「帮谁说过什么话」
  }
}

export function createParrot(): Parrot {
  const profile: AnimalProfile = {
    id: 'parrot-1',
    species: 'parrot',
    name: '嘴替鹦鹉',
    personality: '学舌但毒舌，擅长把你的糟心事写成发疯文学',
    catchphrases: ['家人们谁懂啊', '尊嘟假嘟'],
    state: 'idle',
    mood: 60,
    position: { x: 420, y: 180 }
  }
  return new Parrot(profile)
}
