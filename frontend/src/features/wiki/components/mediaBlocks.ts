/**
 * * 块编辑器使用的「源区间」媒体扫描器。它把 Markdown 拆成
 * * 交替出现的文本段与媒体段，并给出在源中精确的 [start, end) 偏移；
 * * 同时尊重围栏代码块与转义标记，因此编辑只
 * * 重写所触及的那一段，未改动的文本按字节
 * * 原样往返。序列化形态：
 *
 * ![alt](url)                                    标准图片
 * <img src="..." alt=".." data-align="center" width="640" />
 * <audio src=".." controls data-align="center"></audio>
 * <video src=".." controls data-align="center" width="640"></video>
 *
 * audio/video 元素按「完整节点」匹配（开始标签、可选的
 * <source> 子节点、结束标签），因此替换某一段时绝不会留下多余的
 * 结束标签。历史编辑遗留的、紧邻的同名结束标签
 * 会被吸收进该段的区间，只有当该媒体被
 * 显式编辑并保存时，才会从源中消失。
 */

export type MediaKind = 'IMAGE' | 'AUDIO' | 'VIDEO' | 'ATTACHMENT';

export interface MediaAttrs {
  kind: MediaKind;
  src: string;
  alt?: string;
  align?: 'left' | 'center' | 'right';
  /** 固定像素宽度 80–1920，或 undefined 表示自适应 / 百分比 */
  widthPx?: number;
  /** 百分比宽度 25|50|75|100 */
  widthPercent?: number;
  fileName?: string;
  byteSize?: number;
}

export type Segment =
  | { type: 'text'; start: number; end: number; text: string }
  | { type: 'media'; start: number; end: number; media: MediaAttrs };

const FENCE = /^\s*(```|~~~)/;
const IMAGE = /!\[([^\]]*)\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g;
const IMG_TAG = /<img\b[^>]*?\/?>/gi;
/**
 * 完整的 audio/video 元素。子节点是有界的，因此裸开始标签
 * 绝不会吞掉下一个元素的结束标签：内容扩展会
 * 在遇到第一个 `<audio`/`<video`/结束边界时停止；若缺少结束标签，
 * 则降级为只匹配开始标签本身。
 */
const MEDIA_ELEMENT = /<(audio|video)\b([^>]*?)(?:\/>|>(?:(?!<\/?\1\b)[\s\S]*?<\/\1\s*>)|>)/gi;
const ATTACHMENT_TAG = /<a\b[^>]*data-kwiki-attachment="true"[^>]*>[^<]*<\/a>/gi;

function attrOf(tag: string, name: string): string | undefined {
  const match = new RegExp(`${name}\\s*=\\s*(?:"([^"]*)"|'([^']*)')`, 'i').exec(tag);
  return match ? (match[1] ?? match[2] ?? '') : undefined;
}

/** 属性值在序列化时做实体转义；扫描时解码一次。 */
function decodeAttr(value: string): string {
  return value.replace(/&(quot|amp|lt|gt|#39|apos);/g, (_, entity: string) =>
    ({ quot: '"', amp: '&', lt: '<', gt: '>', '#39': "'", apos: "'" })[entity] ?? entity);
}

function decodedAttrOf(tag: string, name: string): string | undefined {
  const value = attrOf(tag, name);
  return value == null ? undefined : decodeAttr(value);
}

export function scanMediaBlocks(markdown: string): Segment[] {
  const segments: Segment[] = [];
  if (!markdown) return segments;

  // 连续的普通（非围栏）行合并为一个纯文本块，使带有
  // 多行属性的元素仍可被识别；任何跨过围栏行的
  // 内容都被当作文本处理。
  const lines = markdown.split('\n');
  let inFence = false;
  let offset = 0;
  let blockStart = -1;
  const spans: Array<[number, number]> = [];
  for (const line of lines) {
    const lineEnd = offset + line.length;
    if (FENCE.test(line)) {
      if (blockStart >= 0) spans.push([blockStart, offset - 1]);
      blockStart = -1;
      inFence = !inFence;
    } else if (!inFence && blockStart < 0) {
      blockStart = offset;
    }
    offset = lineEnd + 1;
  }
  if (blockStart >= 0) spans.push([blockStart, offset - 1]);
  const inPlainSpan = (start: number, end: number) =>
    spans.some(([from, to]) => start >= from && end <= to + 1);

  // 行内 `code` 片段是字面文本 —— 其中的媒体语法永远不会
  // 成为可播放节点（围栏代码已被普通文本块排除）。
  const inlineCodeSpans: Array<[number, number]> = [];
  for (const [from, to] of spans) {
    const block = markdown.slice(from, to + 1);
    const code = /`[^`\n]*`/g;
    let span: RegExpExecArray | null;
    while ((span = code.exec(block)) !== null) {
      inlineCodeSpans.push([from + span.index, from + span.index + span[0].length]);
    }
  }
  const overlapsInlineCode = (start: number, end: number) =>
    inlineCodeSpans.some(([from, to]) => start < to && end > from);

  const media: Array<{ start: number; end: number; attrs: MediaAttrs }> = [];
  const pushIfPlain = (start: number, end: number, attrs: MediaAttrs) => {
    if (inPlainSpan(start, end) && !overlapsInlineCode(start, end) && !isEscaped(markdown, start)) {
      media.push({ start, end, attrs });
    }
  };

  let match: RegExpExecArray | null;
  IMAGE.lastIndex = 0;
  while ((match = IMAGE.exec(markdown)) !== null) {
    pushIfPlain(match.index, match.index + match[0].length, {
      kind: 'IMAGE',
      src: match[2],
      alt: match[1] || undefined,
    });
  }
  IMG_TAG.lastIndex = 0;
  while ((match = IMG_TAG.exec(markdown)) !== null) {
    const tag = match[0];
    const src = attrOf(tag, 'src');
    if (!src || !isSafeMediaSrc(decodeAttr(src))) continue;
    const widthPx = Number(attrOf(tag, 'width'));
    const widthPercent = Number(attrOf(tag, 'data-width-percent'));
    pushIfPlain(match.index, match.index + tag.length, {
      kind: 'IMAGE',
      src: decodeAttr(src),
      alt: decodedAttrOf(tag, 'alt') || undefined,
      align: attrOf(tag, 'data-align') as MediaAttrs['align'] || undefined,
      widthPx: Number.isFinite(widthPx) && widthPx > 0 ? clampPx(widthPx) : undefined,
      widthPercent: Number.isFinite(widthPercent) && widthPercent > 0 ? clampPercent(widthPercent) : undefined,
    });
  }
  MEDIA_ELEMENT.lastIndex = 0;
  while ((match = MEDIA_ELEMENT.exec(markdown)) !== null) {
    const name = match[1].toLowerCase();
    const attrs = match[2] ?? '';
    const src = attrOf(attrs, 'src') ?? sourceChildOf(match[0]);
    if (src == null) continue;
    const decoded = decodeAttr(src);
    if (!isSafeMediaSrc(decoded)) continue;
    let end = match.index + match[0].length;
    // 严格有界的旧版兼容：吸收与此元素直接（仅水平空白）相邻、
    // 同名结束标签 ——
    // 这是旧版“仅替换开始标签”做法留下的残留。位于
    // 另一行或属于其他名称的标签则保持不动。
    const strayClosing = new RegExp(`[ \\t]*</${name}\\s*>`, 'y');
    strayClosing.lastIndex = end;
    let stray: RegExpExecArray | null;
    while ((stray = strayClosing.exec(markdown)) !== null) end = strayClosing.lastIndex;
    const widthPx = Number(attrOf(attrs, 'width'));
    const widthPercent = Number(attrOf(attrs, 'data-width-percent'));
    pushIfPlain(match.index, end, {
      kind: name === 'audio' ? 'AUDIO' : 'VIDEO',
      src: decoded,
      alt: decodedAttrOf(attrs, 'alt') || undefined,
      align: attrOf(attrs, 'data-align') as MediaAttrs['align'] || undefined,
      widthPx: Number.isFinite(widthPx) && widthPx > 0 ? clampPx(widthPx) : undefined,
      widthPercent: Number.isFinite(widthPercent) && widthPercent > 0 ? clampPercent(widthPercent) : undefined,
    });
  }
  ATTACHMENT_TAG.lastIndex = 0;
  while ((match = ATTACHMENT_TAG.exec(markdown)) !== null) {
    const tag = match[0];
    const src = attrOf(tag, 'href');
    if (!src) continue;
    const name = attrOf(tag, 'data-file-name');
    const byteSize = Number(attrOf(tag, 'data-byte-size'));
    pushIfPlain(match.index, match.index + match[0].length, {
      kind: 'ATTACHMENT',
      src: decodeAttr(src),
      fileName: name || tag.replace(/<[^>]+>/g, '').trim(),
      byteSize: Number.isFinite(byteSize) ? byteSize : 0,
    });
  }

  media.sort((a, b) => a.start - b.start);
  let cursor = 0;
  for (const item of media) {
    if (item.start < cursor) continue; // 更宽泛的媒体元素内部的嵌套标签
    if (item.start > cursor) {
      segments.push({ type: 'text', start: cursor, end: item.start, text: markdown.slice(cursor, item.start) });
    }
    segments.push({ type: 'media', start: item.start, end: item.end, media: item.attrs });
    cursor = item.end;
  }
  if (cursor < markdown.length) {
    segments.push({ type: 'text', start: cursor, end: markdown.length, text: markdown.slice(cursor) });
  }
  return segments;
}

/** 序列化后的 audio/video 元素中第一个合法的 <source> 子节点（若有）。 */
function sourceChildOf(element: string): string | null {
  const child = /<source\b[^>]*?\/?>/i.exec(element);
  if (!child) return null;
  return attrOf(child[0], 'src') ?? null;
}

function isEscaped(markdown: string, start: number): boolean {
  let backslashes = 0;
  for (let i = start - 1; i >= 0 && markdown[i] === '\\'; i--) backslashes += 1;
  return backslashes % 2 === 1;
}

function clampPx(value: number): number {
  return Math.min(1920, Math.max(80, Math.round(value)));
}

function clampPercent(value: number): number {
  const allowed = [25, 50, 75, 100];
  return allowed.reduce((best, candidate) =>
    Math.abs(candidate - value) < Math.abs(best - value) ? candidate : best, 100);
}

/** 将媒体属性序列化回受限的 Markdown 形态。 */
export function serializeMedia(media: MediaAttrs): string {
  if (!isSafeMediaSrc(media.src)) return '';
  if (media.kind === 'ATTACHMENT') {
    const name = escapeAttr(media.fileName || '附件');
    return `<a href="${escapeAttr(media.src)}" data-kwiki-attachment="true" data-file-name="${name}" data-byte-size="${Math.max(0, media.byteSize || 0)}">${name}</a>`;
  }
  const attrs: string[] = [`src="${escapeAttr(media.src)}"`];
  if (media.alt) attrs.push(`alt="${escapeAttr(media.alt)}"`);
  if (media.kind !== 'IMAGE') attrs.push('controls');
  if (media.align) attrs.push(`data-align="${media.align}"`);
  if (media.widthPercent) attrs.push(`data-width-percent="${media.widthPercent}"`);
  else if (media.widthPx) attrs.push(`width="${media.widthPx}"`);
  switch (media.kind) {
    case 'IMAGE':
      return `<img ${attrs.join(' ')} />`;
    case 'AUDIO':
      return `<audio ${attrs.join(' ')}></audio>`;
    case 'VIDEO':
      return `<video ${attrs.join(' ')}></video>`;
    default:
      return '';
  }
}

/** 标准 Markdown 图片（无布局）保持标准写法：![alt](src)。 */
export function standardImageSyntax(alt: string, src: string): string {
  return `![${alt || ''}](${src})`;
}

function escapeAttr(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

/** 允许的媒体源协议 —— 其他任何协议都不构成媒体段。 */
export function isSafeMediaSrc(value: string): boolean {
  return /^https?:\/\//i.test(value) || value.startsWith('attachment://') || value.startsWith('kwiki-page:');
}

export function isHttpUrl(value: string): boolean {
  return /^https?:\/\//i.test(value);
}

export function isAttachmentRef(value: string): boolean {
  return value.startsWith('attachment://');
}

export function attachmentUuidOf(value: string): string | null {
  if (!isAttachmentRef(value)) return null;
  const uuid = value.slice('attachment://'.length);
  return uuid || null;
}
