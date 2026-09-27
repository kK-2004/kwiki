import { nextTick } from 'vue';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { render } from '@testing-library/vue';
import SourceWatermark from '../src/features/wiki/components/SourceWatermark.vue';

function stubSize(width: number, height: number) {
  return vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({
    width, height, top: 0, left: 0, right: width, bottom: height, x: 0, y: 0, toJSON: () => ({}),
  } as DOMRect);
}

describe('SourceWatermark 预览水印', () => {
  afterEach(() => vi.restoreAllMocks());

  it('每个平铺格都显示水印文本，且对读屏隐藏', () => {
    stubSize(400, 300);
    const view = render(SourceWatermark, { props: { text: 'zhangsan · 2026-09-27 10:00' } });
    const root = view.getByTestId('source-watermark');
    expect(root.getAttribute('aria-hidden')).toBe('true');
    const tiles = root.querySelectorAll('span');
    expect(tiles.length).toBeGreaterThan(0);
    tiles.forEach(tile => expect(tile.textContent).toBe('zhangsan · 2026-09-27 10:00'));
  });

  it('平铺数量随容器尺寸增长，保证大区域也被铺满', async () => {
    stubSize(400, 300);
    const small = render(SourceWatermark, { props: { text: 'a' } });
    await nextTick();
    const smallCount = small.getByTestId('source-watermark').querySelectorAll('span').length;
    small.unmount();

    stubSize(1800, 1200);
    const large = render(SourceWatermark, { props: { text: 'a' } });
    await nextTick();
    const largeCount = large.getByTestId('source-watermark').querySelectorAll('span').length;
    expect(largeCount).toBeGreaterThan(smallCount);

    // 旋转后网格按对角线铺开：对角线 ≈ 2164px，列宽 240、行高 110
    const grid = large.getByTestId('source-watermark').firstElementChild as HTMLElement;
    expect(parseInt(grid.style.width, 10)).toBeGreaterThanOrEqual(Math.hypot(1800, 1200) - 1);
  });
});
