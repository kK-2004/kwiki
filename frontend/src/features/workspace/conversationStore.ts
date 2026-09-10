import { defineStore } from 'pinia';
import { api, errorMessage } from '../wiki/api';
import { initialState, openStream, reduce, replayStoredEvents, type ActivityStep, type StreamState } from '../wiki/sse';
export type Session = { id: string; title: string; createdAt?: string; updatedAt?: string };
type Message = { id: number; role: string; content: string; createdAt: string; requestId?: string | null };
type StoredEvent = { seq: number; event_type: string; payload_json: string };
let disposeStream: (() => void) | null = null;
let generation = 0;
let selection = 0;
export const useConversationStore = defineStore('conversation', {
  state: () => ({ sessions: [] as Session[], history: [] as Message[], state: initialState() as StreamState,
    /** 回放/持久化的活动时间线，以产生它的 run 为键。 */
    runActivities: {} as Record<string, ActivityStep[]>,
    query: '', lastQuery: '', sessionId: '', requestId: '', running: false, minimized: false, floatingOpen: false,
    selectedKnowledgeBaseIds: [] as number[], selectedPageIds: [] as number[],
    loaded: false, loading: false, listError: '', historyError: '', }),
  getters: {
    answer: s => s.state.answer, progress: s => s.state.progress, reasoning: s => s.state.reasoning, error: s => s.state.error,
    hasActiveConversation: s => Boolean(s.running || s.sessionId || s.query),
  },
  actions: {
    /** 拉取已存储的运行事件，只保留合并后的活动时间线。 */
    async hydrateRunActivity(requestId: string) {
      if (!this.sessionId || !requestId || this.runActivities[requestId]) return;
      try {
        const events = await api.json<StoredEvent[]>(`/chat/sessions/${encodeURIComponent(this.sessionId)}/runs/${encodeURIComponent(requestId)}/events`);
        const replayed = replayStoredEvents(events);
        this.runActivities = { ...this.runActivities, [requestId]: replayed.activitySteps };
      } catch { /* 没有存储事件的旧运行则不显示任何时间线 */ }
    },
    async hydrateHistoryActivities() {
      const targets = this.history.filter(m => m.role === 'ASSISTANT' && m.requestId).slice(-10);
      for (const message of targets) {
        if (message.requestId && !this.runActivities[message.requestId]) await this.hydrateRunActivity(message.requestId);
      }
    },
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
      try { const detail = await api.json<{ messages: Message[] }>(`/chat/sessions/${encodeURIComponent(id)}`); if (ticket === selection) { this.history = detail.messages; void this.hydrateHistoryActivities(); } }
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
      }, { sessionId: this.sessionId || undefined, clientMessageId: crypto.randomUUID(),
        knowledgeBaseIds: this.selectedKnowledgeBaseIds, pageIds: this.selectedPageIds });
    },
    async finishTurn(run: number) {
      const id = this.sessionId;
      // 重新加载前，先将实时时间线挂接到已结束的 run 上。
      if (this.requestId && this.state.activitySteps.length) {
        this.runActivities = { ...this.runActivities, [this.requestId]: this.state.activitySteps };
      }
      if (id) {
        try {
          const detail = await api.json<{ messages: Message[] }>(`/chat/sessions/${encodeURIComponent(id)}`);
          if (run === generation && this.sessionId === id && !this.running) {
            this.history = detail.messages;
            void this.hydrateHistoryActivities();
            if (this.history.at(-1)?.role === 'ASSISTANT' && this.history.at(-1)?.content === this.state.answer) this.state.answer = '';
          }
        } catch { /* 若历史记录无法刷新，则保留流式返回的结果。 */ }
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
