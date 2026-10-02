import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render } from '@testing-library/vue';
import { createMemoryHistory, createRouter } from 'vue-router';
import { defineComponent } from 'vue';
import ChunkPreview from '../src/features/wiki/components/ChunkPreview.vue';
import { api } from '../src/features/wiki/api';
import { parseExcerpt, plainExcerpt } from '../src/features/wiki/imageBlocks';

const flushPromises = () => new Promise(resolve => setTimeout(resolve, 0));
const START = '<<KWIKI_META_DATA_START {"type":"image","contentId":202}>>';
const END = '<<KWIKI_META_DATA_END {"type":"image","contentId":202}>>';

describe('片段图片受保护块解析', () => {
  it('完整块解析为图片段，前后文字保留', () => {
    const segments = parseExcerpt(`前文\n${START}\n折线图摘要\n${END}\n后文`);
    expect(segments).toEqual([
      { kind: 'text', text: '前文\n' },
      { kind: 'image', contentId: 202, summary: '折线图摘要' },
      { kind: 'text', text: '\n后文' },
    ]);
    expect(plainExcerpt(segments)).not.toContain('KWIKI_META');
  });

  it('片段在块中间被切断时仍识别为图片', () => {
    expect(parseExcerpt(`${START} 只有开头`)).toEqual([{ kind: 'image', contentId: 202, summary: '只有开头' }]);
    expect(parseExcerpt(`只有结尾 ${END} 正文`)).toEqual([
      { kind: 'image', contentId: 202, summary: '只有结尾' },
      { kind: 'text', text: ' 正文' },
    ]);
  });

  it('没有标记的片段原样作为文字', () => {
    expect(parseExcerpt('普通正文')).toEqual([{ kind: 'text', text: '普通正文' }]);
  });
});

describe('命中片段图片预览', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('含图片的片段按 chunk 拉取预览链接，芯片显示缩略图且标签不含标记', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/', name: 'workspace', component: defineComponent({ template: '<div />' }) }],
    });
    const json = vi.spyOn(api, 'json').mockImplementation(async (path: string) => {
      if (path.startsWith('/citations/')) {
        return { resources: [{ contentId: 202, previewUrl: 'https://cdn.example/202.png' }] } as never;
      }
      return { title: '财报' } as never;
    });
    const view = render(ChunkPreview, {
      props: {
        source: {
          kbId: 1, childChunkKey: 'chunk-1', parentChunkKey: 'p-1', resourceType: 'PAGE', resourceId: 19,
          revisionId: 1, headingPath: '财报', charStart: 0, charEnd: 10,
          excerpt: `${START}\n季度收入与净利润趋势折线图\n${END}`,
        },
      },
      global: { plugins: [router] },
    });
    await flushPromises();
    await flushPromises();

    expect(json).toHaveBeenCalledWith('/citations/chunk-1');
    const img = view.container.querySelector('img');
    expect(img?.getAttribute('src')).toBe('https://cdn.example/202.png');
    expect(view.container.textContent).not.toContain('KWIKI_META');
    expect(view.container.textContent).toContain('季度收入');
  });

  it('不含图片的片段不请求引用接口', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/', name: 'workspace', component: defineComponent({ template: '<div />' }) }],
    });
    const json = vi.spyOn(api, 'json').mockResolvedValue({ title: '财报' } as never);
    render(ChunkPreview, {
      props: {
        source: {
          kbId: 1, childChunkKey: 'chunk-2', parentChunkKey: 'p-2', resourceType: 'PAGE', resourceId: 19,
          revisionId: 1, headingPath: '财报', charStart: 0, charEnd: 4, excerpt: '普通正文',
        },
      },
      global: { plugins: [router] },
    });
    await flushPromises();
    expect(json).not.toHaveBeenCalledWith(expect.stringContaining('/citations/'));
  });
});
