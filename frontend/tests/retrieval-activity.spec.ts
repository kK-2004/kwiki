import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render } from '@testing-library/vue';
import { nextTick } from 'vue';
import RetrievalActivity from '../src/features/wiki/components/RetrievalActivity.vue';
import ThinkingStream from '../src/features/wiki/components/ThinkingStream.vue';
import { initialState, reduce, type ActivityStep } from '../src/features/wiki/sse';
import { locateChunk } from '../src/features/wiki/components/locateChunk';
const step: ActivityStep = { stepId: 'g', phase: 'GENERATION', status: 'STARTED', startedAt: 1000, queryRound: 1, summary: '正在生成候选回答' };
afterEach(() => vi.useRealTimers());
describe('retrieval activity', () => {
  it('ticks without new events and follows the active phase', async () => {
    vi.useFakeTimers(); vi.setSystemTime(2000);
    const view = render(RetrievalActivity, { props: { steps: [step], running: true, failed: false } });
    expect(view.getByTestId('retrieval-activity-toggle').getAttribute('aria-expanded')).toBe('true');
    expect(view.getAllByText('正在生成候选回答')).toHaveLength(2);
    expect(view.getByText('· 1.0s')).toBeTruthy();
    await vi.advanceTimersByTimeAsync(1500);
    expect(view.getByText('· 2.5s')).toBeTruthy();
    await view.rerender({ running: false, steps: [{ ...step, status: 'COMPLETED', durationMs: 2500 }] });
    expect(view.getByTestId('retrieval-activity-toggle').getAttribute('aria-expanded')).toBe('false');
    await vi.advanceTimersByTimeAsync(1000);
    expect(view.getByText('· 2.5s')).toBeTruthy(); view.unmount();
  });
  it('keeps thinking on its step, preserves it at completion, and deduplicates replay', () => {
    let state = { ...initialState(), activitySteps: [step] };
    const frame = { type: 'reasoning-summary' as const, requestId: 'r', sequence: 2, payload: { stepId: 'g', text: '核对证据' } };
    state = reduce(state, frame); state = reduce(state, frame);
    state = reduce(state, { type: 'activity', requestId: 'r', sequence: 3, payload: { ...step, status: 'COMPLETED' } });
    expect(state.activitySteps[0].thinking).toBe('核对证据'); expect(state.answer).toBe('');
  });
  it('stops following when the user scrolls up and resumes at the bottom', async () => {
    const frame = () => new Promise(resolve => requestAnimationFrame(() => resolve(null)));
    const view = render(ThinkingStream, { props: { text: '第一行\n最新行' } });
    const toggle = view.getByRole('button');
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    await fireEvent.click(view.getByText('最新行'));
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    const area = view.getByRole('region');
    Object.defineProperties(area, { scrollHeight: { value: 1000, configurable: true }, clientHeight: { value: 200 } });
    area.scrollTop = 100; await fireEvent.scroll(area);
    await view.rerender({ text: '第一行\n最新行\n更多内容' }); await nextTick(); await frame();
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(area.scrollTop).toBe(100);
    area.scrollTop = 800; await fireEvent.scroll(area);
    await view.rerender({ text: '再一行' }); await nextTick(); await frame();
    expect(area.scrollTop).toBe(1000); view.unmount();
  });
  it('shows quality review scores when the step carries them', () => {
    const quality: ActivityStep = { stepId: 'q', phase: 'QUALITY', status: 'COMPLETED', startedAt: 1000, durationMs: 800, queryRound: 1, summary: '质量评审通过', metrics: { relevance: 0.9134, coverage: 0.82, faithfulness: 0.95, supportedEvidenceCount: 3 } };
    const view = render(RetrievalActivity, { props: { steps: [quality], running: false, failed: false, expandedInitial: true } });
    expect(view.getByText('相关性 0.91')).toBeTruthy();
    expect(view.getByText('覆盖度 0.82')).toBeTruthy();
    expect(view.getByText('忠实度 0.95')).toBeTruthy();
    view.unmount();
  });
  it('locates a chunk across inline markup and layout whitespace', () => {
    const container = document.createElement('div'); container.innerHTML = '<p>前文</p><p>目标 <strong>命中</strong>内容</p>';
    document.body.append(container);
    const scroll = vi.fn(); container.querySelectorAll('p').forEach(p => p.scrollIntoView = scroll);
    const located = locateChunk(container, { excerpt: '目标\n命中内容', charStart: 2 });
    expect(located.status).toBe('located');
    expect(window.getSelection()?.toString()).toBe(''); expect(scroll).toHaveBeenCalled();
    expect(locateChunk(container, { excerpt: '已经删除的文本' }).status).toBe('missing');
    located.cleanup(); container.remove();
  });
});

it('previews full chunks and links to the wiki chunk target with the wiki title', async () => {
  const { api } = await import('../src/features/wiki/api');
  const { default: ChunkPreview } = await import('../src/features/wiki/components/ChunkPreview.vue');
  const fetch = vi.spyOn(api, 'json').mockResolvedValue({ title: '部署指南' });
  const view = render(ChunkPreview, { props: { source: { childChunkKey: 'C/1', parentChunkKey: 'P1', kbId: 2, resourceId: 7, resourceType: 'PAGE', revisionId: 1, headingPath: '', charStart: 10, charEnd: 20, excerpt: '这是一段完整的命中内容，用来预览。' } } });
  await nextTick(); await nextTick();
  await fireEvent.click(view.getByRole('button', { name: /部署指南：这是一段完整/ }));
  expect(view.getByText('这是一段完整的命中内容，用来预览。')).toBeTruthy();
  expect(view.getByRole('link').getAttribute('href')).toBe('#/knowledge-bases/2/7?chunk=C%2F1');
  view.unmount(); fetch.mockRestore();
});
