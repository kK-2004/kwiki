import { expect, it } from 'vitest';
import { renderMarkdown } from '../src/features/wiki/components/render';
it('keeps code literal, renders lists/tables, and escapes executable HTML', () => {
  const html = renderMarkdown('# 标题\n\n- 第一项\n- 第二项\n\n| 列一 | 列二 |\n| --- | --- |\n| 内容 | 内容 |\n\n```html\n<script>alert(1)</script>\n**literal**\n```\n\n<img src=x onerror=alert(1)>\n[危险](javascript:alert(1))');
  expect(html).toContain('<ul><li>第一项</li>'); expect(html).toContain('<table>');
  expect(html).toContain('&lt;'); expect(html).toContain('script'); expect(html).toContain('**literal**'); expect(html).not.toContain('<script>'); expect(html).not.toContain('<img'); expect(html).not.toContain('href="javascript:');
});
