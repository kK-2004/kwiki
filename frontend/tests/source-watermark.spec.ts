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
  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it('每个平铺格都显示“身份 · 当前时间”，且对读屏隐藏', () => {
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] });
    vi.setSystemTime(new Date(2026, 8, 27, 10, 5, 30));
    stubSize(400, 300);
    const view = render(SourceWatermark, { props: { identity: '张三 zhangsan' } });
    const root = view.getByTestId('source-watermark');
    expect(root.getAttribute('aria-hidden')).toBe('true');
    const tiles = root.querySelectorAll('span');
    expect(tiles.length).toBeGreaterThan(0);
    tiles.forEach(tile => expect(tile.textContent).toBe('张三 zhangsan · 2026-09-27 10:05'));
  });

  it('时间随时钟走动：跨过整分钟后水印刷新', async () => {
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] });
    vi.setSystemTime(new Date(2026, 8, 27, 10, 5, 30));
    const view = render(SourceWatermark, { props: { identity: 'zhangsan' } });
    const firstTile = () => view.getByTestId('source-watermark').querySelector('span')!.textContent;
    expect(firstTile()).toBe('zhangsan · 2026-09-27 10:05');

    // 30 秒后跨入下一分钟
    await vi.advanceTimersByTimeAsync(30_000);
    await nextTick();
    expect(firstTile()).toBe('zhangsan · 2026-09-27 10:06');

    // 之后每分钟继续刷新
    await vi.advanceTimersByTimeAsync(60_000);
    await nextTick();
    expect(firstTile()).toBe('zhangsan · 2026-09-27 10:07');
  });

  it('卸载后停止计时，不再保留定时器', () => {
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] });
    const view = render(SourceWatermark, { props: { identity: 'zhangsan' } });
    expect(vi.getTimerCount()).toBeGreaterThan(0);
    view.unmount();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('平铺数量随容器尺寸增长，保证大区域也被铺满', async () => {
    stubSize(400, 300);
    const small = render(SourceWatermark, { props: { identity: 'a' } });
    await nextTick();
    const smallCount = small.getByTestId('source-watermark').querySelectorAll('span').length;
    small.unmount();

    stubSize(1800, 1200);
    const large = render(SourceWatermark, { props: { identity: 'a' } });
    await nextTick();
    const largeCount = large.getByTestId('source-watermark').querySelectorAll('span').length;
    expect(largeCount).toBeGreaterThan(smallCount);

    // 旋转后网格按对角线铺开：对角线 ≈ 2164px
    const grid = large.getByTestId('source-watermark').firstElementChild as HTMLElement;
    expect(parseInt(grid.style.width, 10)).toBeGreaterThanOrEqual(Math.hypot(1800, 1200) - 1);
  });
});
