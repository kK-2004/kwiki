import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/vue';
import { createPinia, setActivePinia } from 'pinia';
import ClarificationPrompt from '../src/features/workspace/ClarificationPrompt.vue';
import { useConversationStore } from '../src/features/workspace/conversationStore';
import { api } from '../src/features/wiki/api';
import { clarificationOf, initialState, reduce, type SseFrame } from '../src/features/wiki/sse';

const flushPromises = () => new Promise(resolve => setTimeout(resolve, 0));
const wire = vi.hoisted(() => ({ callbacks: [] as Array<(f: SseFrame) => void>, options: [] as Array<Record<string, unknown>>, abort: vi.fn() }));
vi.mock('../src/features/wiki/sse', async original => ({
  ...await original<object>(),
  openStream: vi.fn((_q: string, cb: (f: SseFrame) => void, options: Record<string, unknown>) => {
    wire.callbacks.push(cb); wire.options.push(options); return wire.abort;
  }),
}));

const clarificationFrame = (): SseFrame => ({
  type: 'clarification', sequence: 2, requestId: 'r1',
  payload: { clarificationId: 'c1', question: '你想了解哪一个？', allowFreeText: true, source: 'router',
    options: [{ id: 'a', label: '部署文档' }, { id: 'b', label: '运维手册' }] },
});

beforeEach(() => { setActivePinia(createPinia()); vi.restoreAllMocks(); localStorage.clear(); wire.callbacks = []; wire.options = []; });

describe('clarification state machine', () => {
  it('parses a clarification frame into state and keeps it after done', () => {
    let state = reduce(initialState(), clarificationFrame());
    state = reduce(state, { type: 'done', sequence: 3, requestId: 'r1', payload: { outcome: 'clarification', clarificationId: 'c1' } });

    expect(state.clarification).toEqual({
      id: 'c1', question: '你想了解哪一个？', allowFreeText: true, source: 'router',
      options: [{ id: 'a', label: '部署文档', kbId: undefined, pageId: undefined }, { id: 'b', label: '运维手册', kbId: undefined, pageId: undefined }],
    });
    expect(state.outcome).toBe('clarification');
    expect(state.terminated).toBe(true);
  });

  it('ignores malformed clarification payloads', () => {
    expect(clarificationOf({ question: '没有 id' })).toBeNull();
    expect(clarificationOf({ clarificationId: 'c', options: [{ id: 1 }, null, 'x'] })?.options).toEqual([]);
  });
});

describe('clarification in the conversation store', () => {
  function mockHistory() {
    vi.spyOn(api, 'json').mockImplementation(async (path: string) => path === '/chat/sessions'
      ? [{ id: 's', title: '它怎么用？' }]
      : { messages: [
        { id: 1, role: 'USER', content: '它怎么用？', createdAt: '' },
        { id: 2, role: 'ASSISTANT', content: '你想了解哪一个？', createdAt: '', requestId: 'r1' }] });
  }

  it('exposes the pending clarification after the run ends and sends the answer with its id', async () => {
    mockHistory();
    const store = useConversationStore();
    store.query = '它怎么用？'; store.send();
    const cb = wire.callbacks[0];
    cb({ type: 'session', sequence: 1, requestId: 'r1', payload: { sessionId: 's' } });
    cb(clarificationFrame());
    cb({ type: 'done', sequence: 3, requestId: 'r1', payload: { outcome: 'clarification', clarificationId: 'c1' } });
    await flushPromises();

    expect(store.pendingClarification?.id).toBe('c1');

    store.answerClarification({ optionId: 'a' }, '部署文档');

    expect(wire.options[1].clarification).toEqual({ id: 'c1', optionId: 'a' });
    expect(wire.options[1].sessionId).toBe('s');
    expect(store.history.at(-1)).toMatchObject({ role: 'USER', content: '部署文档' });
    expect(store.pendingClarification).toBeNull();
  });

  it('free-text answers carry the text and a normal question carries no clarification', async () => {
    mockHistory();
    const store = useConversationStore();
    store.query = '它怎么用？'; store.send();
    const cb = wire.callbacks[0];
    cb({ type: 'session', sequence: 1, requestId: 'r1', payload: { sessionId: 's' } });
    cb(clarificationFrame());
    cb({ type: 'done', sequence: 3, requestId: 'r1', payload: { outcome: 'clarification' } });
    await flushPromises();

    store.answerClarification({ text: '运维手册里的备份' }, '运维手册里的备份');
    expect(wire.options[1].clarification).toEqual({ id: 'c1', text: '运维手册里的备份' });

    store.state.terminated = true; store.running = false;
    store.query = '一个全新的问题'; store.send();
    expect(wire.options[2].clarification).toBeUndefined();
  });

  it('does nothing when there is no pending clarification', () => {
    const store = useConversationStore();
    store.answerClarification({ optionId: 'a' }, '部署文档');
    expect(wire.callbacks).toHaveLength(0);
  });

  it('restores the pending clarification from stored run events for the last assistant message', async () => {
    const store = useConversationStore();
    store.sessionId = 's';
    store.history = [
      { id: 1, role: 'USER', content: '它怎么用？', createdAt: '' },
      { id: 2, role: 'ASSISTANT', content: '你想了解哪一个？', createdAt: '', requestId: 'r1' }];
    vi.spyOn(api, 'json').mockResolvedValue([
      { seq: 1, type: 'clarification', payloadJson: JSON.stringify(clarificationFrame().payload) },
      { seq: 2, type: 'done', payloadJson: '{"outcome":"clarification"}' }]);

    await store.hydrateRunActivity('r1');

    expect(store.pendingClarification?.id).toBe('c1');
    store.history.push({ id: 3, role: 'USER', content: '部署文档', createdAt: '' });
    expect(store.pendingClarification).toBeNull();
  });
});

describe('ClarificationPrompt', () => {
  const clarification = { id: 'c1', question: '你想了解哪一个？', allowFreeText: true, source: 'router',
    options: [{ id: 'a', label: '部署文档' }, { id: 'b', label: '运维手册' }] };

  it('emits the chosen option', async () => {
    const { emitted } = render(ClarificationPrompt, { props: { clarification, showQuestion: true } });
    expect(screen.getByText('你想了解哪一个？')).toBeTruthy();

    await fireEvent.click(screen.getByRole('button', { name: '运维手册' }));

    expect(emitted().choose[0]).toEqual([{ id: 'b', label: '运维手册' }]);
  });

  it('submits trimmed free text and clears the input', async () => {
    const { emitted } = render(ClarificationPrompt, { props: { clarification } });
    const input = screen.getByLabelText('补充说明') as HTMLInputElement;

    await fireEvent.update(input, '  备份策略  ');
    await fireEvent.click(screen.getByRole('button', { name: '发送' }));

    expect(emitted().submit[0]).toEqual(['备份策略']);
    expect(input.value).toBe('');
  });

  it('hides the question when asked, and the free-text form when not allowed', () => {
    render(ClarificationPrompt, { props: { clarification: { ...clarification, allowFreeText: false }, showQuestion: false } });
    expect(screen.queryByText('你想了解哪一个？')).toBeNull();
    expect(screen.queryByLabelText('补充说明')).toBeNull();
  });

  it('disables every control while disabled', () => {
    render(ClarificationPrompt, { props: { clarification, disabled: true } });
    expect((screen.getByRole('button', { name: '部署文档' }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByLabelText('补充说明') as HTMLInputElement).disabled).toBe(true);
  });
});
