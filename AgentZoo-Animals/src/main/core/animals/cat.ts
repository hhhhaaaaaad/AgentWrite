import { Animal, type AnimalProfile, type PerceivedEvent } from './base'

// 摸鱼猫：氛围位（解压陪聊），负责动物园的「生气」，不承担硬性任务。
export class Cat extends Animal {
  perceive(events: PerceivedEvent[]): void {
    // TODO(P0): 对几乎所有事件都懒洋洋地瞄一眼
    void events
  }

  async plan(): Promise<void> {
    // TODO(P0): 大概率 idle（摆烂），偶尔被触发时 socializing
  }

  async act(): Promise<void> {
    // TODO(P0): 冒泡吐槽，走纯模板，几乎不耗 token
  }

  async reflect(): Promise<void> {
    // TODO(P1): 几乎不反思（符合猫设）
  }
}

export function createCat(): Cat {
  const profile: AnimalProfile = {
    id: 'cat-1',
    species: 'cat',
    name: '摸鱼猫',
    personality: '摆烂、慵懒，看透一切，口头禅是「别算了，摆烂吧」',
    catchphrases: ['别算了，摆烂吧', '喵', '关我什么事'],
    state: 'idle',
    mood: 20,
    position: { x: 720, y: 300 }
  }
  return new Cat(profile)
}
