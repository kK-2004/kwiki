import { describe, expect, it } from 'vitest';
import { extractOutline } from '../src/features/wiki/pageOutline';

describe('extractOutline 页面大纲', () => {
  it('按正文顺序提取 h1–h4 标题，忽略更深层级', () => {
    const outline = extractOutline('<h1>规范</h1><p>正文</p><h2>0. 角色与 <strong>边界</strong></h2><h3>细则</h3><h5>忽略</h5><h4>附注</h4>');
    expect(outline).toEqual([
      { index: 0, level: 1, text: '规范' },
      { index: 1, level: 2, text: '0. 角色与 边界' },
      { index: 2, level: 3, text: '细则' },
      { index: 3, level: 4, text: '附注' },
    ]);
  });

  it('空正文没有大纲', () => {
    expect(extractOutline('')).toEqual([]);
    expect(extractOutline('<p>只有段落</p>')).toEqual([]);
  });
});
