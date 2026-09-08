import { defineStore } from 'pinia';
import { api, errorMessage } from '../wiki/api';
import { initialState, openStream, reduce, type StreamState } from '../wiki/sse';
export type Session = { id: string; title: string; createdAt?: string; updatedAt?: string };
type Message = { id: number; role: string; content: string; createdAt: string };
let disposeStream: (() => void) | null = null;
let generation = 0;
let selection = 0;
export const useConversationStore = defineStore('conversation', {
  state: () => ({ sessions: [] as Session[], history: [] as Message[], state: initialState() as StreamState,
    query: '', lastQuery: '', sessionId: '', requestId: '', running: false, minimized: false, floatingOpen: false,
    loaded: false, loading: false, listError: '', historyError: '', }),
  getters: {
    answer: s => s.state.answer, progress: s => s.state.progress, reasoning: s => s.state.reasoning, error: s => s.state.error,
    hasActiveConversation: s => Boolean(s.running || s.sessionId || s.query),
  },
  actions: {
    async loadSessions() {
      this.listError = '';
      try { this.sessions = await api.json<Session[]>('/chat/sessions'); this.loaded = true; }
      catch (e) { this.listError = errorMessage(e, '聊天记录加载失败'); }
    },
    async boot(id?: string) { if (!this.loaded) await this.loadSessions(); if (id && id !== this.sessionId) await this.select(id); },
    async select(id: string) {
      if (!id || (id === this.sessionId && this.running)) return;
      const ticket = ++selection;
      await this.cancel();
      if (ticket !== selection) return;
      this.sessionId = id; this.history = []; this.state = initialState(); this.query = ''; this.historyError = ''; this.loading = true;
      try { const detail = await api.json<{ messages: Message[] }>(`/chat/sessions/${encodeURIComponent(id)}`); if (ticket === selection) this.history = detail.messages; }
      catch (e) { if (ticket === selection) this.historyError = errorMessage(e, '会话加载失败，请重试'); }
      finally { if (ticket === selection) this.loading = false; }
    },
    async newConversation() {
      ++selection;
      await this.cancel();
      this.sessionId = ''; this.history = []; this.query = ''; this.lastQuery = ''; this.state = initialState();
      this.minimized = false; this.floatingOpen = false; this.historyError = ''; this.loading = false;
    },
    send() {
      const current = this.query.trim(); if (!current || this.running || this.loading || this.historyError) return;
      const run = ++generation; this.running = true; this.state = initialState(); this.requestId = ''; this.lastQuery = current; this.query = '';
      this.history.push({ id: -Date.now(), role: 'USER', content: current, createdAt: new Date().toISOString() });
      disposeStream?.();
      disposeStream = openStream(current, frame => {
        if (run !== generation) return;
        this.requestId = frame.requestId || this.requestId; this.state = reduce(this.state, frame);
        if (frame.type === 'session' && this.state.sessionId) {
          this.sessionId = this.state.sessionId;
          if (!this.sessions.some(s => s.id === this.sessionId)) this.sessions.unshift({ id: this.sessionId, title: current.slice(0, 60), updatedAt: new Date().toISOString() });
        }
        if (this.state.terminated) {
          this.running = false; disposeStream = null;
          void this.finishTurn(run);
        }
      }, { sessionId: this.sessionId || undefined, clientMessageId: crypto.randomUUID() });
    },
    async finishTurn(run: number) {
      const id = this.sessionId;
      if (id) {
        try {
          const detail = await api.json<{ messages: Message[] }>(`/chat/sessions/${encodeURIComponent(id)}`);
          if (run === generation && this.sessionId === id && !this.running) {
            this.history = detail.messages;
            if (this.history.at(-1)?.role === 'ASSISTANT' && this.history.at(-1)?.content === this.state.answer) this.state.answer = '';
          }
        } catch { /* Keep the streamed result if history cannot be refreshed. */ }
      }
      await this.loadSessions();
    },
    minimize() { this.floatingOpen = false; this.minimized = true; },
    restore() { this.floatingOpen = true; this.minimized = false; },
    async cancel() {
      const wasRunning = this.running; const id = this.sessionId; const request = this.requestId; const partial = this.state.answer;
      ++generation; disposeStream?.(); disposeStream = null; this.running = false;
      if (wasRunning && id && request) {
        try { await api.post(`/chat/sessions/${encodeURIComponent(id)}/runs/${encodeURIComponent(request)}/cancel`, { partialAnswer: partial }); }
        catch (e) { this.state.error = errorMessage(e, '停止请求未确认，稍后可重新打开此会话'); }
      }
    },
    async rename(session: Session, title: string) { await api.patch(`/chat/sessions/${encodeURIComponent(session.id)}`, { title }); session.title = title; },
    async remove(id: string) { if (this.sessionId === id) await this.cancel(); await api.delete(`/chat/sessions/${encodeURIComponent(id)}`); this.sessions = this.sessions.filter(s => s.id !== id); if (this.sessionId === id) await this.newConversation(); },
  },
});
