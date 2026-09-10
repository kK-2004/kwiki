/**
 * 为阅读器转换经服务端净化的 HTML：真实的 <img>/<audio>/<video>
 * 元素被替换为挂载标记，由 MediaMountRegion 转换为
 * 共享的 KMediaViewer 实例，使阅读器展示媒体的方式与
 * 源卡片和编辑器预览完全一致。代码内容在服务端
 * 以转义文本形式存在，因此这里的字面标签示例不会作为元素出现。
 */
import { isSafeMediaSrc } from './mediaBlocks';
import type { MediaAttrs } from './mediaBlocks';

const MOUNT_CLASS = 'kwiki-media-mount';

export function mediaHtmlToMarkers(html: string): string {
  if (typeof DOMParser === 'undefined' || !html.includes('<')) return html;
  const doc = new DOMParser().parseFromString(html, 'text/html');
  const elements = Array.from(doc.body.querySelectorAll('img, audio, video'));
  for (const element of elements) {
    const src = element.getAttribute('src') || '';
    if (!src || !isSafeMediaSrc(src)) continue;
    const kind: MediaAttrs['kind'] =
      element.tagName === 'IMG' ? 'IMAGE' : element.tagName === 'AUDIO' ? 'AUDIO' : 'VIDEO';
    const marker = doc.createElement('div');
    marker.className = MOUNT_CLASS;
    marker.setAttribute('data-kwiki-kind', kind);
    marker.setAttribute('data-kwiki-src', src);
    const alt = element.getAttribute('alt');
    if (alt) marker.setAttribute('data-kwiki-alt', alt);
    const align = element.getAttribute('data-align');
    if (align) marker.setAttribute('data-kwiki-align', align);
    const percent = element.getAttribute('data-width-percent');
    if (percent) marker.setAttribute('data-kwiki-width-percent', percent);
    const width = element.getAttribute('width');
    if (width) marker.setAttribute('data-kwiki-width', width);
    element.replaceWith(marker);
  }
  return doc.body.innerHTML;
}

export function mediaFromMarker(marker: Element): MediaAttrs | null {
  const kind = marker.getAttribute('data-kwiki-kind');
  const src = marker.getAttribute('data-kwiki-src');
  if (!kind || !src) return null;
  const attrs: MediaAttrs = { kind: kind as MediaAttrs['kind'], src };
  const alt = marker.getAttribute('data-kwiki-alt');
  if (alt) attrs.alt = alt;
  const align = marker.getAttribute('data-kwiki-align');
  if (align === 'left' || align === 'center' || align === 'right') attrs.align = align;
  const percent = Number(marker.getAttribute('data-kwiki-width-percent'));
  if (Number.isFinite(percent) && percent > 0) attrs.widthPercent = percent;
  const width = Number(marker.getAttribute('data-kwiki-width'));
  if (Number.isFinite(width) && width > 0) attrs.widthPx = width;
  return attrs;
}

export function markerKeyOf(marker: Element): string {
  return [
    marker.getAttribute('data-kwiki-kind'),
    marker.getAttribute('data-kwiki-src'),
    marker.getAttribute('data-kwiki-align'),
    marker.getAttribute('data-kwiki-width-percent'),
    marker.getAttribute('data-kwiki-width'),
  ].join('|');
}
