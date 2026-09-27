/** 解析器显示名：与后端 ParserCatalog 一致，接口与存储仍使用原标识。 */
const LABELS: Record<string, string> = { "kwiki-parse-1": "tika-v1", "kwiki-parse-2": "pdfbox-v2" };

export function parserLabel(id: string): string {
  return LABELS[id] ?? id;
}
