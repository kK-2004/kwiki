import { describe, expect, it } from 'vitest';
import { render, fireEvent } from '@testing-library/vue';
import PageEditor from '../src/features/wiki/components/PageEditor.vue';
import MarkdownEditorAdapter from '../src/features/wiki/components/MarkdownEditorAdapter.vue';
import { createTestingPinia } from './test-pinia';
import { useWikiStore } from '../src/features/wiki/store';
import { renderMarkdown } from '../src/features/wiki/components/render';

describe('editor workflow', () => {
  it('previews pending markdown without publishing', async () => {
    const screen = render(PageEditor, {
      props: { kbId: 1, pageId: 7 },
      global: { plugins: [createTestingPinia()] },
    });
    useWikiStore().page = {
      revisionNo: 1,
      markdown: '# 标题',
      html: '',
      createdBy: 1,
      createdAt: '2026-08-16T10:00:00',
    };

    await fireEvent.update(screen.getByTestId('markdown-editor'), '## 预览标题');
    await fireEvent.click(screen.getByTestId('editor-preview'));

    expect(screen.getByTestId('editor-preview-content').innerHTML)
      .toContain('<h2>预览标题</h2>');
    expect(screen.getByTestId('editor-dirty').textContent).toContain('未保存');
  });

  it('renders only safe markdown in preview (escape first)', () => {
    const html = renderMarkdown('# A\n<script>alert(1)</script>\n**b**');
    expect(html).toContain('<h1>A</h1>');
    expect(html).toContain('<strong>b</strong>');
    expect(html).not.toContain('<script>');
  });

  it('markdown editor adapter round-trips source without loss', () => {
    const source = '# 标题\n\n| a | b |\n| --- | --- |\n| 1 | 2 |';
    const screen = render(MarkdownEditorAdapter, { props: { modelValue: source } });
    const textarea = screen.getByTestId('markdown-editor') as HTMLTextAreaElement;
    expect(textarea.value).toBe(source);
  });
});
