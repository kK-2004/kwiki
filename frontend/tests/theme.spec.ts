import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { resetThemeForTest, THEME_STORAGE_KEY, useTheme } from '../src/theme/useTheme';

// 可手动触发 change 事件的 matchMedia 桩
function stubMatchMedia(initialDark: boolean) {
  const listeners = new Set<(event: MediaQueryListEvent) => void>();
  const media = {
    matches: initialDark,
    media: '(prefers-color-scheme: dark)',
    addEventListener: (_: string, fn: (event: MediaQueryListEvent) => void) => listeners.add(fn),
    removeEventListener: (_: string, fn: (event: MediaQueryListEvent) => void) => listeners.delete(fn),
  };
  vi.stubGlobal('matchMedia', vi.fn(() => media));
  return {
    setDark(dark: boolean) {
      media.matches = dark;
      listeners.forEach((fn) => fn({ matches: dark } as MediaQueryListEvent));
    },
  };
}

const root = () => document.documentElement;

describe('useTheme', () => {
  beforeEach(() => {
    localStorage.clear();
    resetThemeForTest();
    delete root().dataset.theme;
    root().classList.remove('dark');
  });
  afterEach(() => vi.unstubAllGlobals());

  it('默认跟随系统，并把系统外观写到 html 上', () => {
    stubMatchMedia(true);
    const { mode, resolved } = useTheme();
    expect(mode.value).toBe('system');
    expect(resolved.value).toBe('dark');
    expect(root().dataset.theme).toBe('dark');
    expect(root().classList.contains('dark')).toBe(true);
  });

  it('system 模式下实时跟随系统外观变化', () => {
    const system = stubMatchMedia(false);
    const { resolved } = useTheme();
    expect(root().dataset.theme).toBe('light');
    system.setDark(true);
    expect(resolved.value).toBe('dark');
    expect(root().dataset.theme).toBe('dark');
  });

  it('手动选择后持久化，并且不再受系统外观影响', () => {
    const system = stubMatchMedia(false);
    const { setMode, resolved } = useTheme();
    setMode('dark');
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark');
    expect(resolved.value).toBe('dark');
    system.setDark(false);
    expect(root().dataset.theme).toBe('dark');
    setMode('light');
    expect(root().classList.contains('dark')).toBe(false);
  });

  it('启动时读取已保存的偏好，非法值回退为 system', () => {
    stubMatchMedia(false);
    localStorage.setItem(THEME_STORAGE_KEY, 'dark');
    expect(useTheme().mode.value).toBe('dark');
    resetThemeForTest();
    localStorage.setItem(THEME_STORAGE_KEY, 'purple');
    expect(useTheme().mode.value).toBe('system');
  });

  it('环境不支持 matchMedia 时按浅色处理', () => {
    vi.stubGlobal('matchMedia', undefined);
    expect(useTheme().resolved.value).toBe('light');
  });
});
