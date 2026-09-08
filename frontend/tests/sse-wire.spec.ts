import { describe, expect, it, vi } from 'vitest';
import { openStream, type SseFrame } from '../src/features/wiki/sse';

describe('real SSE framing', () => {
  it.each(['\n', '\r\n'])('accepts Spring event/data fields without optional spaces and split chunks (%j)', async newline => {
    const encoder = new TextEncoder();
    const wire = ['event:session', 'data:{"seq":1,"requestId":"r","sessionId":"s"}', '', 'event:token', 'data:{"seq":2,"requestId":"r","text":"你好"}', '', 'event:done', 'data:{"seq":3,"requestId":"r"}', '', ''].join(newline);
    const bytes = encoder.encode(wire);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(new ReadableStream({ start(controller) { for (let i=0;i<bytes.length;i+=7) controller.enqueue(bytes.slice(i,i+7)); controller.close(); } }))));
    const frames: SseFrame[] = [];
    await new Promise<void>(resolve => { openStream('问题', frame => { frames.push(frame); if (frame.type === 'done' || frame.type === 'error') resolve(); }); });
    expect(frames.map(f => f.type)).toEqual(['session','token','done']); expect(frames[1].payload.text).toBe('你好');
  });
  it('reports a premature EOF instead of leaving the input permanently busy', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('event:token\ndata:{"text":"部分回答"}\n\n')));
    const frames: SseFrame[] = [];
    await new Promise<void>(resolve => openStream('问题', frame => { frames.push(frame); if (frame.type === 'error') resolve(); }));
    expect(frames.at(-1)?.payload.error).toContain('连接提前中断');
  });
});
