<template>
  <template v-if="route.name !== 'conversations'">
    <section v-if="store.floatingOpen" class="chat-float" role="dialog" aria-label="问问 kwiki" @keydown.esc="onEscape">
      <header>
        <div>
          <span class="float-mark"><i class="i-lucide-sparkles" /></span>
          <strong>问问 kwiki</strong>
          <small>{{ store.running ? '回答生成中' : '你的知识助手' }}</small>
        </div>
        <div class="float-actions">
          <button class="ui-icon" data-testid="float-new-conversation" aria-label="新建会话" :title="store.running ? '回答生成中，暂不能新建会话' : '新建会话'" :disabled="store.running" @click="newConversation"><i class="i-lucide-square-pen" /></button>
          <button ref="historyButton" class="ui-icon" data-testid="float-history" aria-label="历史会话" title="历史会话" :aria-expanded="panel === 'history'" @click="toggleHistory"><i class="i-lucide-history" /></button>
          <button class="ui-icon" aria-label="最小化聊天" @click="store.minimize"><i class="i-lucide-minus" /></button>
          <button class="ui-icon" aria-label="展开完整聊天" @click="expand"><i class="i-lucide-maximize-2" /></button>
        </div>
      </header>
      <div v-if="panel === 'history'" ref="historyPanel" class="history-panel" data-testid="float-history-panel" role="region" aria-label="历史会话列表" tabindex="-1">
        <p v-if="store.listError" class="history-state" role="alert">{{ store.listError }}<button type="button" @click="store.loadSessions()">重新加载</button></p>
        <p v-else-if="!store.loaded" class="history-state">正在加载会话列表…</p>
        <p v-else-if="!store.sessions.length" class="history-state">暂无历史会话</p>
        <ul v-else class="history-list" role="list">
          <li v-for="session in store.sessions" :key="session.id">
            <button
              type="button"
              class="history-session"
              :data-testid="`history-session-${session.id}`"
              :disabled="store.running"
              :title="store.running && session.id !== store.sessionId ? '回答生成中，暂不能切换会话' : undefined"
              @click="pickSession(session.id)"
            >
              <span class="history-title">{{ session.title || '未命名会话' }}</span>
              <i v-if="session.id === store.sessionId" class="i-lucide-check" aria-label="当前会话" />
            </button>
          </li>
        </ul>
        <p v-if="store.running" class="history-hint">回答生成中，暂不能切换会话；可展开完整页或等当前回答完成。</p>
      </div>
      <ConversationMessages v-else compact />
    </section>
    <button v-else type="button" class="launcher" aria-label="问问 kwiki" @click="open"><i class="i-lucide-sparkles" /><span>{{ store.running ? '正在回答…' : '问问 kwiki' }}</span><i class="i-lucide-chevron-up" /></button>
  </template>
</template>
<script setup lang="ts">
import { nextTick, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useConversationStore } from './conversationStore';
import ConversationMessages from './ConversationMessages.vue';
const store = useConversationStore();
const router = useRouter();
const route = useRoute();
/** 浮窗内部面板状态：消息视图与历史列表互斥，均不挂载路由级会话页。 */
const panel = ref<'messages' | 'history'>('messages');
const historyPanel = ref<HTMLElement | null>(null);
const historyButton = ref<HTMLButtonElement | null>(null);

/** 关闭历史面板时把焦点交还给触发按钮，避免焦点落到被遮挡页面。 */
function closeHistoryPanel() {
  if (panel.value !== 'history') return;
  panel.value = 'messages';
  historyButton.value?.focus();
}
function open() { panel.value = 'messages'; store.restore(); void store.boot(); }

/**
 * 新建会话完全委托全局 store；浮窗随后重新展开，
 * 不导航到完整聊天页，也不中断流状态的既有语义。
 */
async function newConversation() {
  if (store.running) return;
  panel.value = 'messages';
  await store.newConversation();
  store.restore();
}

async function toggleHistory() {
  if (panel.value === 'history') { closeHistoryPanel(); return; }
  panel.value = 'history';
  if (!store.loaded) void store.loadSessions();
  await nextTick();
  // 焦点保持在浮窗内：打开列表即聚焦面板容器。
  const target = historyPanel.value ?? (document.activeElement as HTMLElement | null);
  target?.focus?.();
}

/**
 * 就地选择历史会话：复用完整页的 select 语义（授权、
 * 时间线回放与证据遮蔽一致）。生成中禁止切换，交由 store 的
 * 单一 run 规则兜底，UI 只做明确的禁用与解释。
 */
function pickSession(id: string) {
  if (store.running || id === store.sessionId) return;
  panel.value = 'messages';
  void store.select(id);
}

/** Escape 先关闭历史面板（并归还焦点），面板已关闭时才最小化浮窗。 */
function onEscape() {
  if (panel.value === 'history') { closeHistoryPanel(); return; }
  store.minimize();
}

function expand() { panel.value = 'messages'; store.floatingOpen = false; void router.push({ name: 'conversations', params: store.sessionId ? { sessionId: store.sessionId } : undefined }); }
</script>
<style scoped>
.launcher{position:fixed;right:26px;bottom:24px;z-index:55;display:flex;align-items:center;gap:10px;border:1px solid #375c43;padding:13px 18px;border-radius:999px;background:#2d4937;color:#f6fff8;box-shadow:0 7px 24px #1f3c2826;cursor:pointer;font-size:13px;font-weight:550}.launcher i:first-child{color:#94c5a0;font-size:19px}.launcher i:last-child{font-size:12px;opacity:.55;margin-left:4px}.launcher:hover{background:#36573f}.chat-float{position:fixed;right:24px;bottom:24px;z-index:60;width:min(440px,calc(100vw - 32px));height:min(640px,calc(100dvh - 90px));display:flex;flex-direction:column;min-height:0;overflow:hidden;background:white;border:1px solid #dce7df;border-radius:18px;box-shadow:0 18px 70px #1b382a26}.chat-float>header{display:flex;align-items:center;flex-shrink:0;padding:12px 16px;border-bottom:1px solid #edf2ed;gap:4px}.chat-float>header>div:first-child{display:grid;grid-template-columns:32px 1fr;gap:3px 9px;align-items:center;margin-right:auto}.float-mark{grid-row:span 2;display:grid;place-items:center;width:32px;height:32px;background:#edf6ef;color:#5a946b;border-radius:10px}.chat-float strong{font-size:13px;color:#48604e}.chat-float small{font-size:10px;color:#97a399}.float-actions{display:flex;align-items:center;gap:2px}.float-actions .ui-icon{width:30px;height:30px;border:0;border-radius:8px;background:none;color:#7b897f;cursor:pointer}.float-actions .ui-icon:hover{background:#f0f6f1;color:#3f7a54}.float-actions .ui-icon[aria-expanded='true']{background:#e7f2ea;color:#2f6f44}.history-panel{flex:1;min-height:0;display:flex;flex-direction:column;padding:10px 12px;overflow:auto;scrollbar-width:thin}.history-list{list-style:none;margin:0;padding:0;display:grid;gap:4px}.history-session{display:flex;align-items:center;gap:8px;width:100%;border:0;border-radius:9px;padding:10px 12px;background:none;text-align:left;font:inherit;font-size:13px;color:#42544a;cursor:pointer}.history-session:hover:not(:disabled){background:#f1f7f2}.history-session:disabled{opacity:.45;cursor:not-allowed}.history-session:disabled:hover{background:#fafcfb}.history-title{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.history-session i{font-size:14px;color:#3f8a5c}.history-state{margin:20px 6px;color:#8b978f;font-size:12px;display:flex;align-items:center;gap:10px}.history-state button{border:1px solid #d5e2d8;border-radius:7px;padding:4px 10px;background:#fff;color:#3f7a54;font:inherit;font-size:11px;cursor:pointer}.history-hint{margin:10px 6px 4px;color:#a08954;font-size:11px;line-height:1.6}@media(max-width:600px){.chat-float{right:12px;bottom:12px;width:calc(100vw - 24px)}.launcher{right:16px;bottom:16px}}
</style>
<style scoped>
.float-actions .ui-icon:disabled{opacity:.42;cursor:not-allowed}.float-actions .ui-icon:disabled:hover{background:none;color:#7b897f}
</style>
