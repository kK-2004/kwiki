/**
 * 每容器独立的跟随底部状态机：用户滚动意图（上滚离底 → PAUSED，
 * 回到阈值内 → FOLLOWING）与程序滚动（帧级合并写入）分离，
 * 因此流式内容只在用户仍停留在底部时才拉动滚动位置。
 */
import { ref, type Ref } from 'vue';

export type FollowTailState = 'FOLLOWING' | 'PAUSED';

export interface FollowTailController {
  state: Ref<FollowTailState>;
  /** 绑定滚动容器（可选传入其内容元素用于高度观察）。 */
  attach(el: HTMLElement, content?: Element): void;
  detach(): void;
  /** 元素 scroll 事件处理器：根据底部距离更新用户跟随状态。 */
  handleUserScroll(): void;
  /** 内容变化通知：仅在 FOLLOWING 时于下一帧合并写入一次滚动。 */
  notifyContentChanged(): Promise<void>;
  /** 立即滚到底部并恢复跟随（会话初始化/切换时）。 */
  scrollToBottom(): void;
}

export const FOLLOW_TAIL_THRESHOLD = 48;

export function useFollowTail(options: { threshold?: number } = {}): FollowTailController {
  const threshold = options.threshold ?? FOLLOW_TAIL_THRESHOLD;
  const state = ref<FollowTailState>('FOLLOWING');
  let el: HTMLElement | null = null;
  let observer: ResizeObserver | null = null;
  let pendingWrite: Promise<void> | null = null;

  function isAtBottom(): boolean {
    if (!el) return true;
    return el.scrollHeight - el.scrollTop - el.clientHeight <= threshold;
  }

  function handleUserScroll() {
    if (!el) return;
    state.value = isAtBottom() ? 'FOLLOWING' : 'PAUSED';
  }

  function notifyContentChanged(): Promise<void> {
    // 帧级合并：同帧内多次通知只产生一次 scrollTop 写入；
    // 程序写入本身不触发 scroll 事件，因此不会误判用户意图。
    if (!el || state.value !== 'FOLLOWING') return Promise.resolve();
    if (!pendingWrite) {
      pendingWrite = new Promise(resolve => {
        requestAnimationFrame(() => {
          pendingWrite = null;
          if (el && state.value === 'FOLLOWING') el.scrollTop = el.scrollHeight;
          resolve();
        });
      });
    }
    return pendingWrite;
  }

  function scrollToBottom() {
    if (!el) return;
    el.scrollTop = el.scrollHeight;
    state.value = 'FOLLOWING';
  }

  function attach(target: HTMLElement, content?: Element) {
    el = target;
    state.value = 'FOLLOWING';
    if (typeof ResizeObserver === 'undefined') return;
    observer?.disconnect();
    observer = new ResizeObserver(() => { void notifyContentChanged(); });
    observer.observe(content ?? target);
  }

  function detach() {
    observer?.disconnect();
    observer = null;
    el = null;
    pendingWrite = null;
  }

  return { state, attach, detach, handleUserScroll, notifyContentChanged, scrollToBottom };
}
