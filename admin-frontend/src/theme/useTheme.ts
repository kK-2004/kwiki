import { computed, readonly, ref, type ComputedRef, type Ref } from 'vue';

export type ThemeMode = 'light' | 'dark' | 'system';
export type ResolvedTheme = 'light' | 'dark';

/** 与 index.html 中防闪烁脚本使用同一个键，修改时两处必须同步。 */
export const THEME_STORAGE_KEY = 'kwiki-admin-theme';
const DARK_QUERY = '(prefers-color-scheme: dark)';

// 模块级单例：整个应用共享同一份主题状态
const mode = ref<ThemeMode>('system');
const systemDark = ref(false);
const resolved = computed<ResolvedTheme>(() =>
  mode.value === 'system' ? (systemDark.value ? 'dark' : 'light') : mode.value,
);
let started = false;
let media: MediaQueryList | null = null;

function readStoredMode(): ThemeMode {
  try {
    const value = localStorage.getItem(THEME_STORAGE_KEY);
    return value === 'light' || value === 'dark' || value === 'system' ? value : 'system';
  } catch {
    return 'system';
  }
}

function apply() {
  const root = document.documentElement;
  root.dataset.theme = resolved.value;
  root.classList.toggle('dark', resolved.value === 'dark');
  root.style.colorScheme = resolved.value;
}

function onSystemChange(event: MediaQueryListEvent) {
  systemDark.value = event.matches;
  apply();
}

function start() {
  if (started) return;
  started = true;
  mode.value = readStoredMode();
  media = typeof matchMedia === 'function' ? matchMedia(DARK_QUERY) : null;
  systemDark.value = media?.matches ?? false;
  media?.addEventListener('change', onSystemChange);
  apply();
}

function setMode(next: ThemeMode) {
  mode.value = next;
  try {
    localStorage.setItem(THEME_STORAGE_KEY, next);
  } catch {
    // 隐私模式等场景写入失败时只在本次会话内生效
  }
  apply();
}

export function useTheme(): {
  mode: Readonly<Ref<ThemeMode>>;
  resolved: ComputedRef<ResolvedTheme>;
  setMode: (next: ThemeMode) => void;
} {
  start();
  return { mode: readonly(mode), resolved, setMode };
}

/** 仅供测试：清空单例状态，下次 useTheme() 重新读取存储与系统外观。 */
export function resetThemeForTest() {
  media?.removeEventListener('change', onSystemChange);
  media = null;
  started = false;
  mode.value = 'system';
  systemDark.value = false;
}
