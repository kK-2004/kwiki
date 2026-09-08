import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
const flushPromises = () => new Promise(resolve => setTimeout(resolve, 0));
import { useConversationStore } from '../src/features/workspace/conversationStore';
import { api } from '../src/features/wiki/api';
import { type SseFrame } from '../src/features/wiki/sse';
const wire = vi.hoisted(() => ({ callbacks: [] as Array<(f: SseFrame) => void>, abort: vi.fn() }));
vi.mock('../src/features/wiki/sse', async original => ({ ...await original<object>(), openStream: vi.fn((_q, cb) => { wire.callbacks.push(cb); return wire.abort; }) }));
beforeEach(() => { setActivePinia(createPinia()); vi.restoreAllMocks(); wire.callbacks = []; });
describe('persistent conversations', () => {
  it('retains the active stream and draft when toggling the floating window', () => {
    const store = useConversationStore(); store.query = '第一问'; store.send();
    const cb = wire.callbacks[0]; cb({ type:'session', sequence:1, requestId:'r', payload:{ sessionId:'s' } });
    store.query = '下一问草稿'; store.minimize(); store.restore();
    cb({ type:'token', sequence:2, requestId:'r', payload:{ text:'回答' } });
    expect(store.answer).toBe('回答'); expect(store.query).toBe('下一问草稿'); expect(store.running).toBe(true); expect(store.sessions[0].id).toBe('s');
  });
  it('does not display a completed answer twice after loading persisted messages', async () => {
    const store = useConversationStore();
    vi.spyOn(api, 'json').mockImplementation(async path => path === '/chat/sessions' ? [{ id:'s', title:'问题' }] : { messages:[{ id:1,role:'USER',content:'问题' },{id:2,role:'ASSISTANT',content:'答案'}] });
    store.query = '问题'; store.send(); const cb = wire.callbacks[0];
    cb({type:'session',sequence:1,requestId:'r',payload:{sessionId:'s'}}); cb({type:'token',sequence:2,requestId:'r',payload:{text:'答案'}}); cb({type:'done',sequence:3,requestId:'r',payload:{}});
    await flushPromises(); expect(store.history).toHaveLength(2); expect(store.answer).toBe('');
  });
  it('ignores frames from the previous run after starting a new conversation', async () => {
    const store = useConversationStore(); vi.spyOn(api, 'post').mockResolvedValue(undefined);
    store.query = '旧问题'; store.send(); const old = wire.callbacks[0];
    await store.newConversation(); store.query = '新问题'; store.send();
    old({type:'token',sequence:1,requestId:'old',payload:{text:'旧答案'}});
    expect(store.answer).toBe(''); expect(store.history[0].content).toBe('新问题');
  });
});
