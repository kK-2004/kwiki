/** 参考引用的正文定位与可见高亮：只在可验证的文本范围上高亮，绝不猜测。 */

export type ChunkLocationStatus = 'located' | 'missing' | 'ambiguous';

export interface ChunkLocation {
  status: ChunkLocationStatus;
  /** 清理当前高亮（Custom Highlight 注册表或 mark 回退）并取消自动超时。 */
  cleanup: () => void;
}

export interface LocateChunkOptions {
  excerpt: string;
  /** 引用命中的字符偏移，用于在多命中时挑选并验证最近的匹配。 */
  charStart?: number;
  /** 高亮自动清理时长；默认 2600ms。 */
  highlightMs?: number;
}

const DEFAULT_HIGHLIGHT_MS = 2600;
/** 多命中时，最近命中与 charStart 的最大可信距离；超出即视为不可验证。 */
const AMBIGUITY_TOLERANCE = 2000;

let activeCleanup: (() => void) | undefined;
let citationHighlightTimer: ReturnType<typeof window.setTimeout> | undefined;

type HighlightRegistry = Map<string, unknown>;

function highlightRegistry(): { highlights: HighlightRegistry; Highlight: new (range: Range) => unknown } | null {
  const api = (globalThis as unknown as {
    CSS?: { highlights?: HighlightRegistry };
    Highlight?: new (range: Range) => unknown;
  });
  if (api.CSS?.highlights && api.Highlight) return { highlights: api.CSS.highlights, Highlight: api.Highlight };
  return null;
}

/*索引偏移量基于纯文本；在保留 DOM 位置的前提下规范化版面空白。 */
export function locateChunk(container: Element, options: LocateChunkOptions): ChunkLocation {
  const { excerpt, charStart = 0, highlightMs = DEFAULT_HIGHLIGHT_MS } = options;
  activeCleanup?.();
  activeCleanup = undefined;
  const noop: ChunkLocation = { status: 'missing', cleanup: () => {} };
  if (!excerpt.trim()) return noop;

  const walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT);
  const positions: { node: Text; offset: number }[] = [];
  let combined = ''; let current: Node | null;
  while ((current = walker.nextNode())) {
    const node = current as Text;
    for (let offset = 0; offset < node.length; offset++) {
      if (/\s/.test(node.data[offset])) continue;
      combined += node.data[offset]; positions.push({ node, offset });
    }
  }
  const quote = excerpt.replace(/\s/g, '');
  const matches: number[] = [];
  for (let at = combined.indexOf(quote); at >= 0; at = combined.indexOf(quote, at + 1)) matches.push(at);
  if (!matches.length) return noop;

  let start: number;
  if (matches.length === 1) {
    start = matches[0];
  } else {
    start = matches.reduce((best, at) => Math.abs(at - charStart) < Math.abs(best - charStart) ? at : best);
    // 重复出现且字符位置无法验证时拒绝高亮，避免命中错误文本。
    if (Math.abs(start - charStart) > AMBIGUITY_TOLERANCE) {
      return { status: 'ambiguous', cleanup: () => {} };
    }
  }
  const first = positions[start]; const last = positions[start + quote.length - 1];
  if (!first || !last) return noop;
  const range = document.createRange();
  range.setStart(first.node, first.offset); range.setEnd(last.node, last.offset + 1);

  let mark: HTMLElement | null = null;
  const registry = highlightRegistry();
  if (registry) {
    if (citationHighlightTimer) window.clearTimeout(citationHighlightTimer);
    registry.highlights.delete('kwiki-citation');
    registry.highlights.set('kwiki-citation', new registry.Highlight(range));
  } else {
    // 无 Custom Highlight API 的浏览器：临时 mark 包裹范围，清理时无损还原文本。
    mark = document.createElement('mark');
    mark.setAttribute('data-kwiki-citation', '');
    try {
      mark.appendChild(range.extractContents());
      range.insertNode(mark);
    } catch {
      mark = null;
    }
  }

  let cleaned = false;
  const cleanup = () => {
    if (cleaned) return;
    cleaned = true;
    if (citationHighlightTimer) window.clearTimeout(citationHighlightTimer);
    citationHighlightTimer = undefined;
    if (registry) registry.highlights.delete('kwiki-citation');
    if (mark?.parentNode) {
      while (mark.firstChild) mark.parentNode.insertBefore(mark.firstChild, mark);
      mark.remove();
    }
    mark = null;
    if (activeCleanup === cleanup) activeCleanup = undefined;
  };
  activeCleanup = cleanup;
  citationHighlightTimer = window.setTimeout(cleanup, highlightMs);
  first.node.parentElement?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  return { status: 'located', cleanup };
}

/** 清理当前活跃的引用高亮（离开页面/组件卸载时调用）。 */
export function clearCitationHighlight(): void {
  activeCleanup?.();
  activeCleanup = undefined;
}
