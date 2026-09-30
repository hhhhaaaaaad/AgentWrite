import { Animal, type AnimalProfile, type PerceivedEvent } from './base'

// 账本狐狸：消费体检 Agent。真实任务 = billAnalysisPipeline（账单分析）。
export class Fox extends Animal {
  perceive(events: PerceivedEvent[]): void {
    // TODO(P0): 只关心账单/消费/订阅相关事件
    void events
  }

  async plan(): Promise<void> {
    // TODO(P0): 有账单任务 → working；否则 idle 或串门
  }

  async act(): Promise<void> {
    // TODO(P0): working → 调用 billAnalysisPipeline 干活，产出体检报告
  }

  async reflect(): Promise<void> {
    // TODO(P1): 更新「又被割韭菜」的长期记忆
  }
}

export function createFox(): Fox {
  const profile: AnimalProfile = {
    id: 'fox-1',
    species: 'fox',
    name: '账本狐狸',
    personality: '精明、毒舌，专抓乱花钱，阴阳你的每一笔消费',
    catchphrases: ['让我看看你又被谁割了', '这笔钱花得毫无意义'],
    state: 'idle',
    mood: 70,
    position: { x: 120, y: 220 }
  }
  return new Fox(profile)
}
