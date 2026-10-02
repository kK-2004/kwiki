/**
 * 检索片段中的多模态受保护块解析：
 * <<KWIKI_META_DATA_START {"type":"image","contentId":202}>> 摘要 <<KWIKI_META_DATA_END {...}>>
 * 片段可能在块中间被切断（只有起始或只有结束标记），两种情况都按图片段处理。
 */
export type ExcerptSegment =
  | { kind: 'text'; text: string }
  | { kind: 'image'; contentId: number; summary: string };

const BLOCK = /<<KWIKI_META_DATA_(START|END) (\{[^}]*\})>>/g;

function imageContentId(json: string): number | null {
  try {
    const meta = JSON.parse(json) as { type?: unknown; contentId?: unknown };
    const id = Number(meta.contentId);
    return meta.type === 'image' && Number.isFinite(id) && id > 0 ? id : null;
  } catch {
    return null;
  }
}

export function parseExcerpt(excerpt: string): ExcerptSegment[] {
  const segments: ExcerptSegment[] = [];
  let cursor = 0;
  let open: { contentId: number; bodyStart: number } | null = null;
  const pushText = (text: string) => {
    if (text.trim()) segments.push({ kind: 'text', text });
  };
  for (const match of excerpt.matchAll(BLOCK)) {
    const [marker, edge, json] = match;
    const at = match.index ?? 0;
    const contentId = imageContentId(json);
    if (edge === 'START') {
      pushText(excerpt.slice(cursor, at));
      open = contentId == null ? null : { contentId, bodyStart: at + marker.length };
    } else if (open) {
      segments.push({ kind: 'image', contentId: open.contentId, summary: excerpt.slice(open.bodyStart, at).trim() });
      open = null;
    } else if (contentId != null) {
      // 片段从块中间开始：结束标记之前的内容都是该图片的摘要
      segments.push({ kind: 'image', contentId, summary: excerpt.slice(cursor, at).trim() });
    } else {
      pushText(excerpt.slice(cursor, at));
    }
    cursor = at + marker.length;
  }
  if (open) {
    // 片段在块中间结束：其后内容都是摘要
    segments.push({ kind: 'image', contentId: open.contentId, summary: excerpt.slice(open.bodyStart).trim() });
  } else {
    pushText(excerpt.slice(cursor));
  }
  return segments;
}

/** 去掉标记后的可读文本，图片段以摘要代替。 */
export function plainExcerpt(segments: ExcerptSegment[]): string {
  return segments.map(segment => (segment.kind === 'text' ? segment.text : segment.summary)).join(' ');
}
