/**
 * 供预览与流式回答共用的小型、带转义的 Markdown 渲染器（renderer）。
 * 支持标准图片、受限的 <img>/<audio>/<video> 媒体标签，其
 * 属性经白名单过滤，并支持 attachment:// 引用。所有内容先
 * 转义；只有白名单内的媒体标记会被输出。挂载
 * 共享媒体查看器的调用方传入 `mediaPlaceholder`，使媒体段以
 * 令牌形式返回，再由调用方拆分为 Vue 管理的组件插槽（slot）。
 */
import { isSafeMediaSrc, scanMediaBlocks, type MediaAttrs } from './mediaBlocks';
import hljs from 'highlight.js/lib/common';
import 'highlight.js/styles/github.css';

export type MediaUrlResolver = (attrs: MediaAttrs) => string | null;

export interface RenderMarkdownOptions {
  resolveMedia?: MediaUrlResolver;
  mediaPlaceholder?: (attrs: MediaAttrs, index: number) => string;
}

export type MarkdownPart =
  | { type: 'html'; html: string }
  | { type: 'media'; media: MediaAttrs };

const escape = (text: string) => text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');

function mediaStyles(attrs: MediaAttrs): { style: string; widthAttr: string } {
  const style: string[] = [];
  let widthAttr = '';
  if (attrs.align === 'center') style.push('display:block;margin-left:auto;margin-right:auto;text-align:center');
  else if (attrs.align === 'right') style.push('display:block;margin-left:auto');
  else if (attrs.align === 'left') style.push('display:block;margin-right:auto');
  if (attrs.widthPercent) widthAttr = `width="${attrs.widthPercent}%"`;
  else if (attrs.widthPx) widthAttr = `width="${attrs.widthPx}"`;
  return { style: style.join(';'), widthAttr };
}

function renderMedia(attrs: MediaAttrs, options: RenderMarkdownOptions, index: number): string {
  if (!isSafeMediaSrc(attrs.src)) return escape(attrs.src);
  if (options.mediaPlaceholder) return options.mediaPlaceholder(attrs, index);
  const resolved = options.resolveMedia ? options.resolveMedia(attrs) : null;
  const url = resolved || attrs.src;
  const { style, widthAttr } = mediaStyles(attrs);
  const styleAttr = style ? ` style="${style}"` : '';
  const alt = attrs.alt ? ` alt="${escape(attrs.alt)}"` : '';
  if (attrs.kind === 'ATTACHMENT') {
    const name = escape(attrs.fileName || '附件');
    const bytes = attrs.byteSize || 0;
    const size = bytes <= 0 ? '大小未知' : bytes < 1024 ? `${bytes} B` : bytes < 1048576 ? `${(bytes / 1024).toFixed(1)} KB` : `${(bytes / 1048576).toFixed(1)} MB`;
    return `<a class="kwiki-attachment-card" href="${escape(url)}" target="_blank" rel="noopener"><span><strong>${name}</strong><small>${size}</small></span><span class="kwiki-attachment-download">下载</span></a>`;
  }
  switch (attrs.kind) {
    case 'IMAGE':
      return `<img src="${escape(url)}"${alt}${widthAttr ? ' ' + widthAttr : ''}${styleAttr} loading="lazy" />`;
    case 'AUDIO':
      return `<audio src="${escape(url)}" controls preload="metadata"${styleAttr}></audio>`;
    case 'VIDEO':
      return `<video src="${escape(url)}" controls preload="metadata"${widthAttr ? ' ' + widthAttr : ''}${styleAttr}></video>`;
    default:
      return '';
  }
}

export function renderMarkdown(markdown: string, options?: RenderMarkdownOptions): string {
  const inline = (text: string): string => {
    const codes: string[] = [];
    let value = escape(text).replace(/`([^`]+)`/g, (_, code: string) => { codes.push(`<code>${code}</code>`); return `\u0000${codes.length - 1}\u0000`; });
    value = value.replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>')
      .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>').replace(/\*([^*]+)\*/g, '<em>$1</em>');
    return value.replace(/\u0000(\d+)\u0000/g, (_, index: string) => codes[Number(index)] || '');
  };

  // 媒体段渲染为真实节点（node）；其余文本内联渲染。
  const segments = scanMediaBlocks(markdown);
  const renderTextBlock = (block: string): string => renderText(block, inline);
  if (segments.length && segments.some(segment => segment.type === 'media')) {
    const parts: string[] = [];
    let mediaIndex = 0;
    for (const segment of segments) {
      if (segment.type === 'media') parts.push(renderMedia(segment.media, options ?? {}, mediaIndex++));
      else parts.push(renderTextBlock(segment.text));
    }
    return parts.join('\n');
  }
  return renderTextBlock(markdown);
}

const PLACEHOLDER_TOKEN = '\u0000kwiki-media\u0000';

/**
 * 将 Markdown 渲染为交替的 html / 媒体两部分。媒体部分
 * 单独返回，以便调用方将其渲染为真实的 Vue 组件
 *（跨重渲染保持稳定），而非第二个 v-html 播放器。
 */
export function renderMarkdownParts(markdown: string): MarkdownPart[] {
  const mediaByToken = new Map<string, MediaAttrs>();
  const html = renderMarkdown(markdown, {
    mediaPlaceholder: (attrs, index) => {
      const token = `${PLACEHOLDER_TOKEN}${index}\u0000`;
      mediaByToken.set(token, attrs);
      return token;
    },
  });
  const parts: MarkdownPart[] = [];
  const pattern = new RegExp(`(\u0000kwiki-media\u0000\\d+\u0000)`, 'g');
  for (const piece of html.split(pattern)) {
    if (!piece) continue;
    const media = mediaByToken.get(piece);
    if (media) parts.push({ type: 'media', media });
    else parts.push({ type: 'html', html: piece });
  }
  return parts;
}

function renderText(markdown: string, inline: (text: string) => string): string {
  const lines = markdown.replace(/\r\n/g, '\n').split('\n');
  const out: string[] = [];
  for (let i = 0; i < lines.length;) {
    const line = lines[i];
    if (!line.trim()) { i++; continue; }
    if (/^\s*```/.test(line)) {
      const language = /^\s*```([\w+-]+)?/.exec(line)?.[1]?.toLowerCase() || '';
      const code: string[] = []; i++;
      while (i < lines.length && !/^\s*```/.test(lines[i])) code.push(lines[i++]);
      if (i < lines.length) i++;
      const source = code.join('\n');
      let highlighted = escape(source);
      let className = '';
      if (language && hljs.getLanguage(language)) {
        highlighted = hljs.highlight(source, { language, ignoreIllegals: true }).value;
        className = ` class="hljs language-${escape(language)}"`;
      } else if (source.trim()) {
        highlighted = hljs.highlightAuto(source).value;
        className = ' class="hljs"';
      }
      out.push(`<div class="code-block"><button type="button" class="code-copy" data-copy-code aria-label="复制代码"><i class="i-lucide-copy" aria-hidden="true"></i><span>复制</span></button><pre><code${className}>${highlighted}</code></pre></div>`); continue;
    }
    const heading = /^(#{1,6})\s+(.+)$/.exec(line);
    if (heading) { out.push(`<h${heading[1].length}>${inline(heading[2])}</h${heading[1].length}>`); i++; continue; }
    if (/^\s*([-*_])(?:\s*\1){2,}\s*$/.test(line)) { out.push('<hr />'); i++; continue; }
    if (line.includes('|') && i + 1 < lines.length && /^\s*\|?\s*:?-{3,}:?\s*(?:\|\s*:?-{3,}:?\s*)+\|?\s*$/.test(lines[i + 1])) {
      const cells = (row: string) => row.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map(cell => inline(cell.trim()));
      const heads = cells(line); i += 2; const rows: string[] = [];
      while (i < lines.length && lines[i].trim() && lines[i].includes('|')) rows.push(`<tr>${cells(lines[i++]).map(cell => `<td>${cell}</td>`).join('')}</tr>`);
      out.push(`<div class="table-scroll"><table><thead><tr>${heads.map(cell => `<th>${cell}</th>`).join('')}</tr></thead><tbody>${rows.join('')}</tbody></table></div>`); continue;
    }
    if (/^\s*[-*+]\s+/.test(line) || /^\s*\d+[.)]\s+/.test(line)) {
      const ordered = /^\s*\d+[.)]\s+/.test(line); const marker = ordered ? /^\s*\d+[.)]\s+/ : /^\s*[-*+]\s+/;
      const items: string[] = [];
      while (i < lines.length && marker.test(lines[i])) items.push(`<li>${inline(lines[i++].replace(marker, ''))}</li>`);
      out.push(`<${ordered ? 'ol' : 'ul'}>${items.join('')}</${ordered ? 'ol' : 'ul'}>`); continue;
    }
    if (/^>\s?/.test(line)) { out.push(`<blockquote>${inline(line.replace(/^>\s?/, ''))}</blockquote>`); i++; continue; }
    const paragraph = [inline(line)]; i++;
    while (i < lines.length && lines[i].trim() && !/^(#{1,6}\s|\s*```|\s*[-*+]\s|\s*\d+[.)]\s|>)/.test(lines[i]) && !(lines[i].includes('|') && /^\s*\|?\s*:?-{3}/.test(lines[i + 1] || ''))) paragraph.push(inline(lines[i++]));
    out.push(`<p>${paragraph.join('<br />')}</p>`);
  }
  return out.join('\n');
}
