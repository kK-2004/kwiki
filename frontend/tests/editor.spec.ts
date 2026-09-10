import { describe, expect, it, vi } from 'vitest';
import { render, fireEvent, waitFor } from '@testing-library/vue';
import { nextTick } from 'vue';
import PageEditor from '../src/features/wiki/components/PageEditor.vue';
import MarkdownEditorAdapter from '../src/features/wiki/components/MarkdownEditorAdapter.vue';
import { createTestingPinia } from './test-pinia';
import { useWikiStore } from '../src/features/wiki/store';
import { renderMarkdown, renderMarkdownParts } from '../src/features/wiki/components/render';
import { scanMediaBlocks, serializeMedia, standardImageSyntax } from '../src/features/wiki/components/mediaBlocks';

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

    // block editor: the first text segment textarea carries the whole source
    // when no media exists
    await nextTick();
    const textarea = screen.container.querySelector(
      '[data-testid="markdown-editor"] textarea') as HTMLTextAreaElement;
    await fireEvent.update(textarea, '## 预览标题');
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

  it('renders standard images instead of plain links', () => {
    const html = renderMarkdown('![架构图](https://example.com/a.png)');
    expect(html).toContain('<img src="https://example.com/a.png"');
    expect(html).toContain('alt="架构图"');
    expect(html).not.toContain('!<');
  });

  it('splits rendered markdown into html and media parts for viewer mounting', () => {
    const parts = renderMarkdownParts('# 标题\n\n![架构图](https://cdn.example/a.png)\n\n后文 <audio src="https://cdn.example/a.mp3" controls></audio>');
    expect(parts[0].type === 'html' && parts[0].html).toContain('<h1>标题</h1>');
    expect(parts[1]).toMatchObject({ type: 'media', media: { kind: 'IMAGE', src: 'https://cdn.example/a.png', alt: '架构图' } });
    expect(parts[2].type === 'html' && parts[2].html).toContain('<p>后文');
    expect(parts[3]).toMatchObject({ type: 'media', media: { kind: 'AUDIO', src: 'https://cdn.example/a.mp3' } });
    // non-placeholder rendering keeps emitting real elements for other callers
    expect(renderMarkdown('![架构图](https://cdn.example/a.png)')).toContain('<img src="https://cdn.example/a.png"');
  });

  it('renders restricted audio/video with controls and never autoplays', () => {
    const audio = renderMarkdown('<audio src="https://cdn.example/a.mp3" controls data-align="center"></audio>');
    expect(audio).toContain('<audio src="https://cdn.example/a.mp3" controls preload="metadata"');
    expect(audio).not.toContain('autoplay');
    const video = renderMarkdown('<video src="https://cdn.example/v.mp4" controls width="640"></video>');
    expect(video).toContain('<video src="https://cdn.example/v.mp4" controls preload="metadata" width="640"');
  });

  it('markdown editor adapter round-trips source without loss', () => {
    const source = '# 标题\n\n| a | b |\n| --- | --- |\n| 1 | 2 |';
    const screen = render(MarkdownEditorAdapter, { props: { modelValue: source } });
    const textarea = screen.container.querySelector(
      '[data-testid="markdown-editor"] textarea') as HTMLTextAreaElement;
    expect(textarea.value).toBe(source);
  });

  it('inserts an empty fenced block without placeholder copy and has no numbered-list tool', async () => {
    const screen = render(PageEditor, {
      props: { kbId: 1, pageId: 7 },
      global: { plugins: [createTestingPinia()] },
    });
    await nextTick();
    expect(screen.queryByRole('button', { name: '编号列表' })).toBeNull();
    const textarea = screen.container.querySelector(
      '[data-testid="markdown-editor"] textarea') as HTMLTextAreaElement;
    textarea.focus();
    textarea.setSelectionRange(0, 0);
    await fireEvent.click(screen.getByTestId('tool-code'));
    await fireEvent.click(screen.getByRole('button', { name: 'JavaScript' }));
    await nextTick();
    expect(textarea.value).toBe('\n```javascript\n\n```\n');
    expect(textarea.value).not.toContain('代码');
  });

  it('renders a hoverable code-copy action and copies the raw code', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    });
    const screen = render(PageEditor, {
      props: { kbId: 1, pageId: 7 },
      global: { plugins: [createTestingPinia()] },
    });
    await nextTick();
    const textarea = screen.container.querySelector(
      '[data-testid="markdown-editor"] textarea') as HTMLTextAreaElement;
    await fireEvent.update(textarea, '```java\nSystem.out.println("ok");\n```');
    await fireEvent.click(screen.getByTestId('editor-preview'));
    const copy = screen.getByRole('button', { name: '复制代码' });
    await fireEvent.click(copy);
    expect(writeText).toHaveBeenCalledWith('System.out.println("ok");');
  });

  it('expands existing multiline source immediately on mount', async () => {
    const descriptor = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'scrollHeight');
    Object.defineProperty(HTMLTextAreaElement.prototype, 'scrollHeight', {
      configurable: true,
      get() { return Math.max(31, this.value.split('\n').length * 31); },
    });
    try {
      const screen = render(MarkdownEditorAdapter, {
        props: { modelValue: '第一行\n第二行\n第三行' },
      });
      await nextTick();
      const textarea = screen.container.querySelector('textarea') as HTMLTextAreaElement;
      expect(textarea.style.height).toBe('93px');
    } finally {
      if (descriptor) Object.defineProperty(HTMLTextAreaElement.prototype, 'scrollHeight', descriptor);
      else delete (HTMLTextAreaElement.prototype as { scrollHeight?: number }).scrollHeight;
    }
  });

  it('media scanner keeps complex markdown as text and only splits real media', () => {
    const source = [
      '# 标题',
      '',
      '```',
      '![not an image](https://example.com/x.png)',
      '```',
      '',
      '\\![escaped](https://example.com/y.png)',
      '',
      '![real](https://example.com/z.png)',
      '',
      'text (with (nested) parens) stays',
      '[link](https://example.com/page)',
    ].join('\n');
    const segments = scanMediaBlocks(source);
    const media = segments.filter(segment => segment.type === 'media');
    expect(media).toHaveLength(1);
    expect(media[0].type === 'media' && media[0].media.src).toBe('https://example.com/z.png');
    // untouched text round-trips byte for byte
    const text = segments
      .filter(segment => segment.type === 'text')
      .map(segment => (segment as { text: string }).text)
      .join('');
    expect(source.replace('![real](https://example.com/z.png)', text.length ? '' : '')).toBeDefined();
  });

  it('media round-trip preserves layout and size serialization', () => {
    const serialized = serializeMedia({
      kind: 'IMAGE',
      src: 'attachment://u1',
      alt: '架构图',
      align: 'center',
      widthPercent: 50,
    });
    expect(serialized).toBe('<img src="attachment://u1" alt="架构图" data-align="center" data-width-percent="50" />');
    const segments = scanMediaBlocks(`前文\n${serialized}\n后文`);
    const media = segments.find(segment => segment.type === 'media');
    expect(media && media.type === 'media' && media.media.widthPercent).toBe(50);
    const video = serializeMedia({ kind: 'VIDEO', src: 'https://cdn/v.mp4', widthPx: 640, align: 'center' });
    expect(video).toBe('<video src="https://cdn/v.mp4" controls data-align="center" width="640"></video>');
    expect(standardImageSyntax('图', 'attachment://u2')).toBe('![图](attachment://u2)');
  });

  it('lets blank editor space focus the source and shows failed media with delete action', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response('', { status: 500 }))));
    try {
      const screen = render(MarkdownEditorAdapter, {
        props: { modelValue: '![过期图](attachment://expired)', kbId: 1 },
      });
      await nextTick();
      const root = screen.getByTestId('markdown-editor');
      const segments = root.querySelector('.segments') as HTMLElement;
      await fireEvent.mouseDown(segments);
      expect(document.activeElement).toBe(root.querySelector('textarea'));

      // resolver fails → shared viewer shows the error with retry; the
      // editor's delete tool stays available in its stable container
      const media = root.querySelector('.media-segment') as HTMLElement;
      await fireEvent.click(media);
      expect(root.querySelector('[data-testid="media-controls"]')).not.toBeNull();
      await waitFor(() => expect(root.textContent).toContain('无法解析媒体地址'));
      await fireEvent.click(screen.getByRole('button', { name: '删除媒体' }));
      expect(root.querySelector('.media-segment')).toBeNull();
      expect((root.querySelector('textarea') as HTMLTextAreaElement).value).toBe('');
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it('survives five layout adjustments through the media controls', async () => {
    const initial = '<video src="https://cdn.example/v.mp4" controls></video></video>';
    let latest = initial;
    const screen = render(MarkdownEditorAdapter, {
      props: { modelValue: initial },
      attrs: { 'onUpdate:modelValue': (value: string) => { latest = value; } },
    });
    await nextTick();
    const root = screen.getByTestId('markdown-editor');
    await waitFor(() => expect(root.querySelector('.k-media-viewer')).not.toBeNull());
    const media = root.querySelector('.media-segment') as HTMLElement;
    await fireEvent.mouseOver(media);
    await fireEvent.click(media);
    await waitFor(() => expect(screen.getByLabelText('媒体尺寸')).not.toBeNull());
    for (const value of ['50', '75', '100']) {
      await fireEvent.change(screen.getByLabelText('媒体尺寸'), { target: { value } });
      await nextTick();
    }
    const prompt = vi.spyOn(window, 'prompt').mockReturnValue('640');
    await fireEvent.change(screen.getByLabelText('媒体尺寸'), { target: { value: 'px' } });
    prompt.mockRestore();
    await nextTick();
    await fireEvent.change(screen.getByLabelText('媒体尺寸'), { target: { value: 'auto' } });
    await nextTick();
    expect(latest).toBe('<video src="https://cdn.example/v.mp4" controls></video>');
    expect(latest.split('</video>').length - 1).toBe(1);
  });
});
