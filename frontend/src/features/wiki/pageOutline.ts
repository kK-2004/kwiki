/** 页面大纲：从渲染后的正文 HTML 提取标题，目录树与阅读区共用同一选择器以按序号对应。 */
import { shallowRef } from 'vue';

export const OUTLINE_SELECTOR = 'h1, h2, h3, h4';

export interface OutlineItem {
  index: number;
  level: number;
  text: string;
}

export function extractOutline(html: string): OutlineItem[] {
  if (!html) return [];
  const doc = new DOMParser().parseFromString(html, 'text/html');
  return [...doc.body.querySelectorAll(OUTLINE_SELECTOR)].map((heading, index) => ({
    index,
    level: Number(heading.tagName.slice(1)),
    text: (heading.textContent ?? '').replace(/\s+/g, ' ').trim(),
  }));
}

/** 目录树大纲点击后请求阅读区滚动到第 index 个标题；nonce 使重复点击同一项也能触发。 */
export const outlineJump = shallowRef<{ index: number; nonce: number } | null>(null);

export function jumpToHeading(index: number): void {
  outlineJump.value = { index, nonce: (outlineJump.value?.nonce ?? 0) + 1 };
}
