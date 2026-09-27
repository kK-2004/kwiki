import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { nextTick } from 'vue';
import { fireEvent, render, screen } from '@testing-library/vue';
import ThemeSwitcher from '../src/theme/ThemeSwitcher.vue';
import { resetThemeForTest, THEME_STORAGE_KEY } from '../src/theme/useTheme';

describe('ThemeSwitcher', () => {
  beforeEach(() => {
    localStorage.clear();
    resetThemeForTest();
    vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: false, addEventListener() {}, removeEventListener() {} })));
  });
  afterEach(() => vi.unstubAllGlobals());

  it('菜单形态：打开后可选择黑夜并持久化，选完关闭菜单', async () => {
    render(ThemeSwitcher, { props: { variant: 'menu' } });
    await fireEvent.click(screen.getByRole('button', { name: '切换主题' }));
    const system = screen.getByRole('menuitemradio', { name: /跟随系统/ });
    expect(system.getAttribute('aria-checked')).toBe('true');
    await fireEvent.click(screen.getByRole('menuitemradio', { name: /黑夜/ }));
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark');
    expect(document.documentElement.dataset.theme).toBe('dark');
    expect(screen.queryByRole('menu')).toBeNull();
    await nextTick();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '切换主题' }));
  });

  it('菜单形态：按 Esc 关闭', async () => {
    render(ThemeSwitcher, { props: { variant: 'menu' } });
    await fireEvent.click(screen.getByRole('button', { name: '切换主题' }));
    await fireEvent.keyDown(screen.getByRole('menu'), { key: 'Escape' });
    expect(screen.queryByRole('menu')).toBeNull();
    await nextTick();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '切换主题' }));
  });

  it('分段形态：三个单选项，点击白天生效', async () => {
    render(ThemeSwitcher, { props: { variant: 'segmented' } });
    expect(screen.getAllByRole('radio')).toHaveLength(3);
    await fireEvent.click(screen.getByRole('radio', { name: /白天/ }));
    expect(screen.getByRole('radio', { name: /白天/ }).getAttribute('aria-checked')).toBe('true');
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('light');
  });
});
