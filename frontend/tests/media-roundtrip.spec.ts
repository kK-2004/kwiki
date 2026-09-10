import { describe, expect, it } from 'vitest';
import { scanMediaBlocks, serializeMedia, type MediaAttrs } from '../src/features/wiki/components/mediaBlocks';
import { renderMarkdown } from '../src/features/wiki/components/render';

/** Applies one editor-style media edit (layout/size) at segment level. */
function editMedia(source: string, index: number, patch: Partial<MediaAttrs>): string {
  const mediaSegments = scanMediaBlocks(source).filter(segment => segment.type === 'media');
  const segment = mediaSegments[index];
  if (!segment || segment.type !== 'media') throw new Error(`media segment ${index} not found`);
  const next = { ...segment.media, ...patch };
  if (patch.widthPercent) next.widthPx = undefined;
  if (patch.widthPx) next.widthPercent = undefined;
  const serialized = serializeMedia(next);
  if (!serialized) throw new Error('serialization refused the media');
  return source.slice(0, segment.start) + serialized + source.slice(segment.end);
}

function mediaOf(source: string, index = 0): MediaAttrs {
  const segments = scanMediaBlocks(source).filter(segment => segment.type === 'media');
  const segment = segments[index];
  if (!segment || segment.type !== 'media') throw new Error(`media segment ${index} not found`);
  return segment.media;
}

const countOccurrences = (source: string, needle: string) => source.split(needle).length - 1;

describe('media complete-node roundtrip', () => {
  it('recognizes complete audio/video elements including their closing tags', () => {
    const source = '前文\n<video src="https://cdn/v.mp4" controls data-align="center" width="640"></video>\n后文';
    const segments = scanMediaBlocks(source);
    expect(segments).toEqual([
      { type: 'text', start: 0, end: 3, text: '前文\n' },
      { type: 'media', start: 3, end: source.length - 3, media: {
        kind: 'VIDEO', src: 'https://cdn/v.mp4', align: 'center', widthPx: 640,
      } },
      { type: 'text', start: source.length - 3, end: source.length, text: '\n后文' },
    ]);
  });

  it('scans multi-line attributes and <source> children', () => {
    const multiline = '<video src="https://cdn/v.mp4"\n  controls\n  data-align="center"></video>';
    expect(mediaOf(`文本\n${multiline}`).src).toBe('https://cdn/v.mp4');
    expect(mediaOf(`文本\n${multiline}`).align).toBe('center');

    const withSourceChild = '<video controls><source src="https://cdn/w.webm" type="video/webm"></video>';
    const media = mediaOf(`文本\n${withSourceChild}`);
    expect(media.kind).toBe('VIDEO');
    expect(media.src).toBe('https://cdn/w.webm');
    // editing normalizes to the canonical single-src form
    const edited = editMedia(`文本\n${withSourceChild}`, 0, { align: 'left' });
    expect(edited).toBe('文本\n<video src="https://cdn/w.webm" controls data-align="left"></video>');
  });

  it('degrades a bare opening tag to the opening-tag range only', () => {
    const source = '<video src="https://cdn/v.mp4">剩下内容';
    const segments = scanMediaBlocks(source);
    expect(segments).toHaveLength(2);
    expect(segments[1].type === 'text' && segments[1].text).toBe('剩下内容');
  });

  it('serializes with escaped attributes and rejects unsafe protocols', () => {
    const serialized = serializeMedia({ kind: 'VIDEO', src: 'https://cdn/v.mp4?a=1&b=2', alt: '说"你好"' });
    expect(serialized).toBe('<video src="https://cdn/v.mp4?a=1&amp;b=2" alt="说&quot;你好&quot;" controls></video>');
    const round = mediaOf(serialized);
    expect(round.src).toBe('https://cdn/v.mp4?a=1&b=2');
    expect(round.alt).toBe('说"你好"');

    expect(serializeMedia({ kind: 'VIDEO', src: 'javascript:alert(1)' })).toBe('');
    expect(scanMediaBlocks('<video src="javascript:alert(1)"></video>').every(segment => segment.type === 'text')).toBe(true);
  });
});

describe('legacy duplicated closing tags', () => {
  it('absorbs only same-name closing tags directly after the element', () => {
    const source = '前\n<video src="https://cdn/v.mp4" controls></video></video> </video>\n中间\n</video>\n后';
    const segments = scanMediaBlocks(source);
    const media = segments.find(segment => segment.type === 'media');
    // element + directly adjacent residue (horizontal whitespace only); the
    // newline-separated one is not 紧随 and must stay text
    expect(media && media.type === 'media' && media.end).toBe(source.indexOf('中间') - 1);
    // one edit removes the redundant adjacent closings, other text byte-identical
    const edited = editMedia(source, 0, { align: 'center' });
    expect(edited).toBe('前\n<video src="https://cdn/v.mp4" controls data-align="center"></video>\n中间\n</video>\n后');
    expect(countOccurrences(edited, '</video>')).toBe(2); // element + untouched distant text
  });

  it('absorbs repeated directly-adjacent residue from historical edits', () => {
    const source = '<audio src="https://cdn/a.mp3" controls></audio></audio></audio>';
    const edited = editMedia(source, 0, { widthPercent: 50 });
    expect(edited).toBe('<audio src="https://cdn/a.mp3" controls data-width-percent="50"></audio>');
  });

  it('keeps a distant closing tag as plain text', () => {
    const source = '<video src="https://cdn/v.mp4" controls></video>\n\n段落\n\n</video>';
    const segments = scanMediaBlocks(source);
    const trailing = segments.at(-1);
    expect(trailing?.type === 'text' && trailing.text).toContain('</video>');
    const edited = editMedia(source, 0, { align: 'left' });
    expect(countOccurrences(edited, '</video>')).toBe(2); // element + untouched text
  });

  it('does not touch media syntax inside code or escaped text', () => {
    const fenced = '```\n<video src="https://cdn/v.mp4" controls></video></video>\n```';
    expect(scanMediaBlocks(fenced).every(segment => segment.type === 'text')).toBe(true);
    const inline = '`<video src="https://cdn/v.mp4"></video>`';
    expect(scanMediaBlocks(inline).every(segment => segment.type === 'text')).toBe(true);
    const escaped = '\\<video src="https://cdn/v.mp4"></video>';
    expect(scanMediaBlocks(escaped).every(segment => segment.type === 'text')).toBe(true);
    // fenced examples also stay literal in previews
    expect(renderMarkdown(fenced)).not.toContain('<video src=');
  });
});

describe('repeated edit stability (five adjustments + save/reopen)', () => {
  it('converges to a single tag set after five layout/size adjustments', () => {
    let source = '前文\n<video src="https://cdn/v.mp4" controls></video></video></video>\n后文';
    const adjustments: Array<Partial<MediaAttrs>> = [
      { align: 'center' },
      { widthPercent: 75 },
      { align: 'left', widthPx: 640 },
      { widthPercent: 100 },
      { align: 'right' },
    ];
    for (const patch of adjustments) source = editMedia(source, 0, patch);
    expect(source).toBe('前文\n<video src="https://cdn/v.mp4" controls data-align="right" data-width-percent="100"></video>\n后文');
    expect(countOccurrences(source, '</video>')).toBe(1);
    expect(countOccurrences(source, '<video')).toBe(1);
  });

  it('is idempotent: re-scanning a serialized document changes nothing', () => {
    let source = '文字\n<audio src="https://cdn/a.mp3" controls></audio></audio>\n<video src="https://cdn/v.mp4"\n data-align="center"></video>\n文字';
    for (let round = 0; round < 5; round += 1) {
      const mediaSegments = scanMediaBlocks(source).filter(segment => segment.type === 'media');
      expect(mediaSegments).toHaveLength(2);
      // "save and reopen": serialize+rescan yields the same segments again
      let rewritten = source;
      for (const segment of mediaSegments.reverse()) {
        if (segment.type !== 'media') continue;
        rewritten = rewritten.slice(0, segment.start) + serializeMedia(segment.media) + rewritten.slice(segment.end);
      }
      expect(rewritten).toBe('文字\n<audio src="https://cdn/a.mp3" controls></audio>\n<video src="https://cdn/v.mp4" controls data-align="center"></video>\n文字');
      source = rewritten;
    }
  });

  it('renders exactly one player and no residue text', () => {
    const edited = editMedia('<video src="https://cdn/v.mp4" controls></video></video>', 0, {});
    const html = renderMarkdown(edited);
    expect(countOccurrences(html, '<video')).toBe(1);
    expect(countOccurrences(html, '</video>')).toBe(1);
    expect(html).not.toContain('</video></video>');
  });
});
