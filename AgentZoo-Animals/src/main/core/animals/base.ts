// 动物基类：每只动物 = 状态机(人设 + 记忆 + 工具) + perceive/plan/act/reflect 循环

export type AnimalSpecies = 'fox' | 'parrot' | 'cat'

export type AnimalState = 'idle' | 'moving' | 'working' | 'socializing' | 'done'

export interface AnimalProfile {
  id: string
  species: AnimalSpecies
  name: string
  /** 人设描述，注入 prompt */
  personality: string
  /** 口头禅 */
  catchphrases: string[]
  state: AnimalState
  /** 当前心情 0-100 */
  mood: number
  /** 俯视地图坐标 */
  position: { x: number; y: number }
}

export interface PerceivedEvent {
  ts: number
  type: string
  content: string
}

export abstract class Animal {
  constructor(public readonly profile: AnimalProfile) {}

  get id(): string {
    return this.profile.id
  }

  get species(): AnimalSpecies {
    return this.profile.species
  }

  /**
   * 感知：只读最近相关事件（选择性注意，模拟「动物只注意得到眼前」）。
   * TODO(P0)
   */
  abstract perceive(events: PerceivedEvent[]): void

  /**
   * 思考：人设 + 记忆 + 当前状态 → 决定本轮行为（状态机迁移）。
   * TODO(P0)
   */
  abstract plan(): Promise<void>

  /**
   * 行动：干活（工具链）/ 说话 / 走动。
   * TODO(P0)
   */
  abstract act(): Promise<void>

  /**
   * 反思：阶段性总结，压缩长期记忆、更新关系（好感度）。
   * TODO(P1)
   */
  abstract reflect(): Promise<void>
}
