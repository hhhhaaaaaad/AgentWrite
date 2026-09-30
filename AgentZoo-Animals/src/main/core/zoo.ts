import type { Animal } from './animals/base'

// 动物园管理器：动物注册表 + tick 调度 + 事件流 + 氛围值
export interface ZooEvent {
  id: string
  ts: number
  type: 'feed' | 'task' | 'say' | 'system'
  from?: string
  content: string
}

export interface ZooSnapshot {
  /** 氛围值：0=岁月静好 ... 100=集体发疯 */
  mood: number
  time: number
  events: ZooEvent[]
}

export class Zoo {
  private animals = new Map<string, Animal>()
  private events: ZooEvent[] = []
  private mood = 50

  register(animal: Animal): void {
    this.animals.set(animal.id, animal)
  }

  get(id: string): Animal | undefined {
    return this.animals.get(id)
  }

  all(): Animal[] {
    return [...this.animals.values()]
  }

  push(event: ZooEvent): void {
    this.events.push(event)
  }

  snapshot(): ZooSnapshot {
    return { mood: this.mood, time: Date.now(), events: [...this.events] }
  }

  /**
   * 心智 tick：低频 + 事件驱动。
   * TODO(P0): 驱动每只动物 perceive → plan → act；空闲动物降频以省 token。
   */
  tick(): void {
    // for (const animal of this.all()) {
    //   animal.perceive(recentEvents)
    //   await animal.plan()
    //   await animal.act()
    // }
  }
}
