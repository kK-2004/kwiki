import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/vue';
import AgenticAnswerPanel from '../src/features/wiki/components/AgenticAnswerPanel.vue';
import { createTestingPinia } from './test-pinia';
import { initialState, parseWireFrame, reduce, TERMINAL_TYPES } from '../src/features/wiki/sse';

function wire(type: string, data: string) {
  return parseWireFrame(type, data);
}

describe('agentic answer panel and SSE state machine', () => {
  it('ignores any frame after a terminal event (no reconnect, no late tokens)', () => {
    let state = initialState();
    const frames = [
      wire('route', '{"seq":1,"requestId":"r","type":"route"}'),
      wire('token', '{"seq":2,"requestId":"r","type":"token","text":"答"}'),
      wire('done', '{"seq":3,"requestId":"r","type":"done"}'),
      wire('token', '{"seq":4,"requestId":"r","type":"token","text":"late"}'),
    ];
    for (const frame of frames) {
      state = reduce(state, frame!);
    }
    expect(state.terminated).toBe(true);
    expect(state.answer).toBe('答');
    expect(state.progress).toEqual(['route']);
  });

  it('accumulates fragmented token events into the answer text', () => {
    let state = initialState();
    for (const text of ['kwiki ', '使用', '外部中间件']) {
      state = reduce(state, wire('token', `{"seq":1,"type":"token","text":"${text}"}`)!);
    }
    expect(state.answer).toBe('kwiki 使用外部中间件');
  });

  it('records sanitized terminal errors', () => {
    let state = initialState();
    state = reduce(state, wire('error', '{"seq":2,"type":"error","error":"retrieval-failed"}')!);
    expect(state.terminated).toBe(true);
    expect(state.error).toBe('知识检索暂时不可用，请稍后重试');
  });

  it('parses citations frames into citation entries', () => {
    let state = initialState();
    state = reduce(
      state,
      wire(
        'citations',
        '{"seq":9,"type":"citations","citations":[{"childChunkKey":"C1","parentChunkKey":"P0","resourceType":"PAGE","resourceId":7,"revisionId":3,"headingPath":"部署","charStart":0,"charEnd":9,"excerpt":"摘录"}]}',
      )!,
    );
    expect(state.citations).toHaveLength(1);
    expect(state.citations[0].childChunkKey).toBe('C1');
  });

  it('keeps repeated tool, QA and retry progress and ignores unknown events', () => {
    let state = initialState();
    for (const type of ['retrieve', 'quality', 'retry', 'tool', 'retrieve', 'quality', 'future-event']) {
      state = reduce(state, wire(type, '{"seq":1,"requestId":"r"}')!);
    }
    expect(state.progress).toEqual(['retrieve', 'quality', 'retry', 'tool', 'retrieve', 'quality']);
    state = reduce(state, wire('done', '{"seq":2,"outcome":"clarification","message":"请说明版本"}')!);
    expect(state.message).toBe('请说明版本');
    expect(state.outcome).toBe('clarification');
  });

  it('terminal types are exactly done and error', () => {
    expect(TERMINAL_TYPES).toEqual(['done', 'error']);
  });

  it('panel renders input and ask control with labels', () => {
    const screen = render(AgenticAnswerPanel, {
      global: { plugins: [createTestingPinia()] },
    });
    expect(screen.getByTestId('agentic-input')).toBeTruthy();
    expect(screen.getByTestId('agentic-ask')).toBeTruthy();
    expect(screen.getByLabelText('提问')).toBeTruthy();
  });
});
