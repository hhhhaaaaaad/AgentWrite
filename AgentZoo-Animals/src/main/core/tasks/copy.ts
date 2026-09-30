// 嘴替鹦鹉任务 pipeline：素材 → 选风格 → 生成

export type CopyStyle = '发疯文学' | '阴阳怪气' | '谜语人' | '已读乱回'

export interface CopyResult {
  style: CopyStyle
  text: string
}

/**
 * TODO(P0): 接入 LLM provider；P0 桩可先用模板生成。
 * TODO(P1): 支持「接狐狸的报告做二次创作」的联动入口。
 */
export async function copywritingPipeline(
  input: string,
  style: CopyStyle
): Promise<CopyResult> {
  return { style, text: `[桩] ${style}版：${input}` }
}
