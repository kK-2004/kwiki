/**
 * SSE client for the chat stream. Parses wire frames into typed events, exposes
 * progress states, forbids reconnection after a terminal event, and cancels on
 * unmount. Wire format: {"seq":n,"requestId":"...","type":"...",...payload}.
 */
export type SseEventType = 'route' | 'rewrite' | 'retrieve' | 'token' | 'citations' | 'done' | 'error';

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
  progress: string[];
  answer: string;
  citations: CitationEntry[];
  error: string | null;
  terminated: boolean;
}

export function initialState(): StreamState {
  return { progress: [], answer: '', citations: [], error: null, terminated: false };
}

export function reduce(state: StreamState, frame: SseFrame): StreamState {
  if (state.terminated) {
    return state;
  }
  switch (frame.type) {
    case 'route':
    case 'rewrite':
    case 'retrieve':
      return {
        ...state,
        progress: [...state.progress, frame.type],
      };
    case 'token':
      return { ...state, answer: state.answer + String(frame.payload.text ?? '') };
    case 'citations':
      return {
        ...state,
        citations: (frame.payload.citations as CitationEntry[]) ?? [],
      };
    case 'done':
      return { ...state, terminated: true };
    case 'error':
      return { ...state, terminated: true, error: String(frame.payload.error ?? 'error') };
    default:
      return state;
  }
}

/** Opens the stream; returns a disposer. never reconnects after termination. */
export function openStream(
  query: string,
  onFrame: (frame: SseFrame) => void,
): () => void {
  const controller = new AbortController();
  void (async () => {
    try {
      const response = await fetch('/api/v1/chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ query }),
        signal: controller.signal,
      });
      if (!response.body) {
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
        buffer += decoder.decode(value, { stream: true });
        let boundary = buffer.indexOf('\n\n');
        while (boundary >= 0) {
          const raw = buffer.slice(0, boundary);
          buffer = buffer.slice(boundary + 2);
          applyRawFrame(raw, onFrame);
          boundary = buffer.indexOf('\n\n');
        }
      }
    } catch {
      /* aborted or network failure: state machine already terminal-safe */
    }
  })();
  return () => controller.abort();
}

function applyRawFrame(raw: string, onFrame: (frame: SseFrame) => void) {
  let type = '';
  const dataLines: string[] = [];
  for (const line of raw.split('\n')) {
    if (line.startsWith('event: ')) {
      type = line.slice(7).trim();
    } else if (line.startsWith('data: ')) {
      dataLines.push(line.slice(6));
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
