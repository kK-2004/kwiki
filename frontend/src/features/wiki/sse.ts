/**
 * SSE client for the chat stream. Parses wire frames into typed events, exposes
 * progress states, forbids reconnection after a terminal event, and cancels on
 * unmount. Wire format: {"seq":n,"requestId":"...","type":"...",...payload}.
 */
import { getAuthGeneration, getAuthToken, setAuthToken } from './api';

export type SseEventType = 'session' | 'route' | 'rewrite' | 'retrieve' | 'tool' | 'quality' | 'retry' | 'reasoning-summary' | 'token' | 'citations' | 'done' | 'error';

export interface SseFrame {
  type: SseEventType;
  sequence: number;
  requestId: string;
  payload: Record<string, unknown>;
}

export interface CitationEntry {
  childChunkKey: string;
  parentChunkKey: string;
  resourceType: string;
  resourceId: number;
  revisionId: number;
  headingPath: string;
  charStart: number;
  charEnd: number;
  excerpt: string;
}

export const TERMINAL_TYPES: SseEventType[] = ['done', 'error'];

export function parseWireFrame(type: string, data: string): SseFrame | null {
  try {
    const parsed = JSON.parse(data) as Record<string, unknown>;
    return {
      type: type as SseEventType,
      sequence: Number(parsed.seq ?? 0),
      requestId: String(parsed.requestId ?? ''),
      payload: parsed,
    };
  } catch {
    return null;
  }
}

export interface StreamState {
  sessionId: string | null;
  lastSequence: number;
  progress: string[];
  progressDetails: string[];
  progressItems: ProgressItem[];
  reasoning: string[];
  answer: string;
  citations: CitationEntry[];
  error: string | null;
  terminated: boolean;
  message?: string;
  outcome?: string;
}

export interface ProgressItem {
  key: string;
  type: string;
  node?: string;
  status?: string;
  summary: string;
  callId?: string;
  round?: number;
  confidence?: number;
  score?: number;
  scoreKind?: string;
  passed?: boolean;
  reason?: string;
  retryRound?: number;
}

export function initialState(): StreamState {
  return { sessionId: null, lastSequence: 0, progress: [], progressDetails: [], progressItems: [], reasoning: [], answer: '', citations: [], error: null, terminated: false };
}

export function reduce(state: StreamState, frame: SseFrame): StreamState {
  if (state.terminated || (frame.sequence > 0 && frame.sequence < state.lastSequence)) {
    return state;
  }
  const advanced = {
    ...state,
    lastSequence: Math.max(state.lastSequence, frame.sequence),
  };
  switch (frame.type) {
    case 'session':
      return { ...advanced, sessionId: String(frame.payload.sessionId ?? '') || null };
    case 'route':
    case 'rewrite':
    case 'retrieve':
    case 'tool':
    case 'quality':
    case 'retry':
      {
        const payload = frame.payload;
        const callId = payload.callId == null ? undefined : String(payload.callId);
        const round = payload.round == null ? undefined : Number(payload.round);
        const key = `${frame.type}:${callId ?? `event-${advanced.progressItems.length}`}:${round ?? 0}`;
        const status = payload.status == null ? undefined : String(payload.status);
        const summary = String(payload.summary ?? payload.message ?? payload.status ?? frame.type);
        const confidence = typeof payload.confidence === 'number' ? payload.confidence : undefined;
        const passed = typeof payload.passed === 'boolean' ? payload.passed : undefined;
        const item: ProgressItem = {
          key, type: frame.type, node: payload.node == null ? undefined : String(payload.node), status,
          summary, callId, round, confidence,
          score: typeof payload.score === 'number' ? payload.score : undefined,
          scoreKind: payload.scoreKind == null ? undefined : String(payload.scoreKind),
          passed, reason: payload.reason == null ? undefined : String(payload.reason),
          retryRound: payload.retryRound == null ? undefined : Number(payload.retryRound),
        };
        const existing = advanced.progressItems.findIndex((entry) => entry.key === key);
        const progressItems = [...advanced.progressItems];
        if (existing >= 0) progressItems[existing] = { ...progressItems[existing], ...item };
        else progressItems.push(item);
        const progress = existing >= 0 ? advanced.progress : [...advanced.progress, frame.type];
        const progressDetails = existing >= 0 ? advanced.progressDetails : [...advanced.progressDetails, summary];
        if (existing >= 0) progressDetails[advanced.progress.indexOf(frame.type)] = summary;
        return { ...advanced, progress, progressDetails, progressItems };
      }
    case 'reasoning-summary':
      return { ...advanced, reasoning: [...state.reasoning, String(frame.payload.summary ?? frame.payload.text ?? '')].filter(Boolean) };
    case 'token':
      return { ...advanced, answer: state.answer + String(frame.payload.text ?? '') };
    case 'citations':
      return {
        ...advanced,
        citations: (frame.payload.citations as CitationEntry[]) ?? [],
      };
    case 'done':
      return { ...advanced, terminated: true, message: String(frame.payload.message ?? ''), outcome: String(frame.payload.outcome ?? '') };
    case 'error':
      return { ...advanced, terminated: true, error: chatError(String(frame.payload.error ?? 'error')) };
    default:
      return advanced;
  }
}

/** Opens the stream; returns a disposer. never reconnects after termination. */
export function openStream(
  query: string,
  onFrame: (frame: SseFrame) => void,
  options: { sessionId?: string; clientMessageId?: string; agentId?: string } = {},
): () => void {
  const controller = new AbortController();
  const requestGeneration = getAuthGeneration();
  let terminated = false;
  const deliver = (frame: SseFrame) => {
    if (terminated) return;
    if (frame.type === 'done' || frame.type === 'error') terminated = true;
    onFrame(frame);
  };
  void (async () => {
    try {
      const response = await fetch('/api/v1/chat/stream', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(getAuthToken() ? { Authorization: `Bearer ${getAuthToken()}` } : {}),
        },
        body: JSON.stringify({ query, ...options }),
        signal: controller.signal,
      });
      if (response.status === 401) {
        deliver({ type: 'error', sequence: 0, requestId: '', payload: { error: '登录已过期，请重新登录' } });
        if (typeof window !== 'undefined') window.dispatchEvent(new CustomEvent('kwiki:unauthenticated'));
        return;
      }
      if (!response.ok) {
        if (response.status === 401 && typeof window !== 'undefined') {
          window.dispatchEvent(new CustomEvent('kwiki:unauthenticated'));
        }
        onFrame({ type: 'error', sequence: 0, requestId: '', payload: { error: `请求失败（${response.status}）` } });
        return;
      }
      const renewed = response.headers.get('X-Auth-Token');
      if (renewed && requestGeneration === getAuthGeneration()) setAuthToken(renewed);
      if (!response.body) {
        deliver({ type: 'error', sequence: 0, requestId: '', payload: { error: '回答连接未建立，请重试' } });
        return;
      }
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      for (;;) {
        const { done, value } = await reader.read();
        if (done) {
          break;
        }
        buffer = (buffer + decoder.decode(value, { stream: true })).replace(/\r\n/g, '\n');
        let boundary = buffer.indexOf('\n\n');
        while (boundary >= 0) {
          const raw = buffer.slice(0, boundary);
          buffer = buffer.slice(boundary + 2);
          applyRawFrame(raw, deliver);
          boundary = buffer.indexOf('\n\n');
        }
      }
      if (!terminated && !controller.signal.aborted) deliver({ type: 'error', sequence: 0, requestId: '', payload: { error: '连接提前中断，已保留收到的回答，请重试' } });
    } catch (error) {
      if ((error as { name?: string })?.name !== 'AbortError') {
        onFrame({ type: 'error', sequence: 0, requestId: '', payload: { error: '网络连接失败，请重试' } });
      }
    }
  })();
  return () => controller.abort();
}

function applyRawFrame(raw: string, onFrame: (frame: SseFrame) => void) {
  let type = '';
  const dataLines: string[] = [];
  for (const line of raw.split('\n')) {
    if (line.startsWith('event:')) {
      type = line.slice(6).trim();
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).replace(/^ /, ''));
    }
  }
  if (!type || dataLines.length === 0) {
    return;
  }
  const frame = parseWireFrame(type, dataLines.join('\n'));
  if (frame) {
    onFrame(frame);
  }
}

function chatError(code: string): string {
  const labels: Record<string, string> = {
    'retrieval-failed': '知识检索暂时不可用，请稍后重试',
    'authorization-changed': '访问权限已发生变化，请重新打开会话后重试',
    'agent-busy': '当前请求较多，请稍后再试',
    'timeout': '回答生成超时，请缩短问题后重试',
    'internal-error': '回答生成失败，请稍后重试',
    'answer-validation-failed': '本次回答未通过来源校验，请重新提问',
  };
  return labels[code] || code;
}
