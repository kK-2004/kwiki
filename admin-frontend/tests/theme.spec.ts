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

describe('管理端 useTheme', () => {
  beforeEach(() => {
    localStorage.clear();
    resetThemeForTest();
    delete root().dataset.theme;
    root().classList.remove('dark');
  });
  afterEach(() => vi.unstubAllGlobals());

  it('默认跟随系统', () => {
    stubMatchMedia(true);
    const { mode, resolved } = useTheme();
    expect(mode.value).toBe('system');
    expect(resolved.value).toBe('dark');
    expect(root().classList.contains('dark')).toBe(true);
  });

  it('system 模式下实时跟随系统外观变化', () => {
    const system = stubMatchMedia(false);
    const { resolved } = useTheme();
    system.setDark(true);
    expect(resolved.value).toBe('dark');
    expect(root().dataset.theme).toBe('dark');
  });

  it('手动选择后用管理端独立的键持久化', () => {
    stubMatchMedia(false);
    const { setMode } = useTheme();
    setMode('dark');
    expect(THEME_STORAGE_KEY).toBe('kwiki-admin-theme');
    expect(localStorage.getItem('kwiki-admin-theme')).toBe('dark');
    expect(root().dataset.theme).toBe('dark');
  });

  it('非法存储值回退为 system', () => {
    stubMatchMedia(false);
    localStorage.setItem(THEME_STORAGE_KEY, 'purple');
    expect(useTheme().mode.value).toBe('system');
  });
});
