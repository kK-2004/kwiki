import type { PluginCatalog } from "./api";

/** 目录可用性与解析器所需能力共同决定能否保存，未知历史插件也必须重新选择。 */
export function pluginSelectionError(catalog: PluginCatalog | null, parserId: string, chunkerId: string, schemaId: string): string {
  if (!catalog) return "插件目录尚未加载";
  const parser = catalog.parsers.find(item => item.id === parserId);
  const chunker = catalog.chunkers.find(item => item.id === chunkerId);
  const schema = catalog.schemas.find(item => item.id === schemaId);
  for (const [label, option] of [["解析器", parser], ["分块器", chunker], ["字段格式", schema]] as const) {
    if (!option) return `请选择可用的${label}`;
    if (!option.available) return `${label}不可用：${option.unavailableReason || option.id}`;
  }
  const missing = parser!.requiredCapabilities.filter(capability => !schema!.capabilities.includes(capability));
  return missing.length ? `所选字段格式缺少能力：${missing.join("、")}` : "";
}
