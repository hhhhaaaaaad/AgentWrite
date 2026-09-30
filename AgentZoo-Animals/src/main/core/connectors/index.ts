// 数据源连接器抽象：Mock → IMAP(邮箱) / CSV / OCR

export interface DataConnector {
  readonly name: string
  ingest(input: unknown): Promise<unknown>
}
