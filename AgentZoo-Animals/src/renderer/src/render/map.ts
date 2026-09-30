// 俯视 2D 动物园地图：展区 / 围栏 / 工作点布局。
// TODO(P0): 定义地图瓦片与各展区坐标，实现 drawMap + 寻路（A*）。

export interface Point {
  x: number
  y: number
}

export interface Enclosure {
  id: string
  name: string
  /** 所属动物 id */
  animalId: string
  /** 工作点：动物干活时走向这里 */
  workSpot: Point
  /** 休息点：动物睡觉时回这里 */
  restSpot: Point
  bounds: { x: number; y: number; w: number; h: number }
}

export const ENCLOSURES: Enclosure[] = [
  {
    id: 'fox-den',
    name: '狐狸窝',
    animalId: 'fox-1',
    workSpot: { x: 140, y: 240 },
    restSpot: { x: 120, y: 220 },
    bounds: { x: 60, y: 160, w: 200, h: 160 }
  },
  {
    id: 'parrot-perch',
    name: '鹦鹉树枝',
    animalId: 'parrot-1',
    workSpot: { x: 440, y: 200 },
    restSpot: { x: 420, y: 180 },
    bounds: { x: 360, y: 120, w: 200, h: 160 }
  },
  {
    id: 'cat-bed',
    name: '猫窝',
    animalId: 'cat-1',
    workSpot: { x: 740, y: 320 },
    restSpot: { x: 720, y: 300 },
    bounds: { x: 660, y: 240, w: 200, h: 160 }
  }
]
