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
/** 用户与页面交互（点击、按键、滚轮、触摸）即视为已看到定位，立即清除高亮。 */
const DISMISS_EVENTS = ['pointerdown', 'keydown', 'wheel', 'touchmove'] as const;

type HighlightRegistry = Map<string, unknown>;

function highlightRegistry(): { highlights: HighlightRegistry; Highlight: new (range: Range) => { clear?: () => void } } | null {
  const api = (globalThis as unknown as {
    CSS?: { highlights?: HighlightRegistry };
    Highlight?: new (range: Range) => { clear?: () => void };
  });
  if (api.CSS?.highlights && api.Highlight) return { highlights: api.CSS.highlights, Highlight: api.Highlight };
  return null;
}

export type QuoteRangeResult =
  | { status: 'located'; range: Range; first: Text }
  | { status: 'missing' | 'ambiguous' };

/**
 * 在正文 DOM 中查找引文对应的文本范围：比较时忽略空白（版面空白与纯文本偏移不一致），
 * 多处命中时取最接近 charStart 的一处；距离超过容差视为无法验证。
 */
export function findQuoteRange(container: Element, quote: string, charStart = 0, tolerance = AMBIGUITY_TOLERANCE): QuoteRangeResult {
  const needle = quote.replace(/\s/g, '');
  if (!needle) return { status: 'missing' };
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
  const matches: number[] = [];
  for (let at = combined.indexOf(needle); at >= 0; at = combined.indexOf(needle, at + 1)) matches.push(at);
  if (!matches.length) return { status: 'missing' };

  let start: number;
  if (matches.length === 1) {
    start = matches[0];
  } else {
    start = matches.reduce((best, at) => Math.abs(at - charStart) < Math.abs(best - charStart) ? at : best);
    // 重复出现且字符位置无法验证时拒绝定位，避免命中错误文本。
    if (Math.abs(start - charStart) > tolerance) return { status: 'ambiguous' };
  }
  const first = positions[start]; const last = positions[start + needle.length - 1];
  if (!first || !last) return { status: 'missing' };
  const range = document.createRange();
  range.setStart(first.node, first.offset); range.setEnd(last.node, last.offset + 1);
  return { status: 'located', range, first: first.node };
}

/*索引偏移量基于纯文本；在保留 DOM 位置的前提下规范化版面空白。 */
export function locateChunk(container: Element, options: LocateChunkOptions): ChunkLocation {
  const { excerpt, charStart = 0, highlightMs = DEFAULT_HIGHLIGHT_MS } = options;
  activeCleanup?.();
  activeCleanup = undefined;
  const found = findQuoteRange(container, excerpt, charStart);
  if (found.status !== 'located') return { status: found.status, cleanup: () => {} };
  const { range } = found;
  const first = { node: found.first };

  let mark: HTMLElement | null = null;
  let highlight: { clear?: () => void } | null = null;
  const registry = highlightRegistry();
  if (registry) {
    highlight = new registry.Highlight(range);
    registry.highlights.set('kwiki-citation', highlight);
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
  // 每次高亮持有自己的定时器与监听，旧高亮的清理不会误伤新高亮
  let timer: ReturnType<typeof window.setTimeout> | undefined;
  const cleanup = () => {
    if (cleaned) return;
    cleaned = true;
    if (timer) window.clearTimeout(timer);
    timer = undefined;
    for (const type of DISMISS_EVENTS) window.removeEventListener(type, cleanup, true);
    if (registry && highlight) {
      // 先清空范围再移除注册：确保浏览器重绘，即便注册表已被替换也不残留
      highlight.clear?.();
      if (registry.highlights.get('kwiki-citation') === highlight) registry.highlights.delete('kwiki-citation');
    }
    highlight = null;
    if (mark?.parentNode) {
      while (mark.firstChild) mark.parentNode.insertBefore(mark.firstChild, mark);
      mark.remove();
    }
    mark = null;
    if (activeCleanup === cleanup) activeCleanup = undefined;
  };
  activeCleanup = cleanup;
  timer = window.setTimeout(cleanup, highlightMs);
  for (const type of DISMISS_EVENTS) window.addEventListener(type, cleanup, { capture: true, passive: true });
  first.node.parentElement?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  return { status: 'located', cleanup };
}

/** 清理当前活跃的引用高亮（离开页面/组件卸载时调用）。 */
export function clearCitationHighlight(): void {
  activeCleanup?.();
  activeCleanup = undefined;
}
