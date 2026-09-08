<template>
  <template v-if="route.name !== 'conversations'">
    <section v-if="store.floatingOpen" class="chat-float" role="dialog" aria-label="问问 kwiki" @keydown.esc="store.minimize"><header><div><span class="float-mark"><i class="i-lucide-sparkles" /></span><strong>问问 kwiki</strong><small>{{ store.running ? '回答生成中' : '你的知识助手' }}</small></div><button class="ui-icon" aria-label="最小化聊天" @click="store.minimize"><i class="i-lucide-minus" /></button><button class="ui-icon" aria-label="展开完整聊天" @click="expand"><i class="i-lucide-maximize-2" /></button><button class="ui-icon" aria-label="关闭聊天浮窗" @click="store.floatingOpen = false"><i class="i-lucide-x" /></button></header><ConversationMessages compact /></section>
    <button v-else type="button" class="launcher" aria-label="问问 kwiki" @click="open"><i class="i-lucide-sparkles" /><span>{{ store.running ? '正在回答…' : '问问 kwiki' }}</span><i class="i-lucide-chevron-up" /></button>
  </template>
</template>
<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'; import { useConversationStore } from './conversationStore'; import ConversationMessages from './ConversationMessages.vue';
const store = useConversationStore(); const router = useRouter(); const route = useRoute();
function open() { store.restore(); void store.boot(); }
function expand() { store.floatingOpen = false; void router.push({ name: 'conversations', params: store.sessionId ? { sessionId: store.sessionId } : undefined }); }
</script>
<style scoped>
.launcher{position:fixed;right:26px;bottom:24px;z-index:55;display:flex;align-items:center;gap:10px;border:1px solid #375c43;padding:13px 18px;border-radius:999px;background:#2d4937;color:#f6fff8;box-shadow:0 7px 24px #1f3c2826;cursor:pointer;font-size:13px;font-weight:550}.launcher i:first-child{color:#94c5a0;font-size:19px}.launcher i:last-child{font-size:12px;opacity:.55;margin-left:4px}.launcher:hover{background:#36573f}.chat-float{position:fixed;right:24px;bottom:24px;z-index:60;width:min(440px,calc(100vw - 32px));height:min(640px,calc(100dvh - 90px));display:flex;flex-direction:column;min-height:0;overflow:hidden;background:white;border:1px solid #dce7df;border-radius:18px;box-shadow:0 18px 70px #1b382a26}.chat-float>header{display:flex;align-items:center;flex-shrink:0;padding:16px;border-bottom:1px solid #edf2ed;gap:1px}.chat-float>header>div{display:grid;grid-template-columns:32px 1fr;gap:3px 9px;align-items:center;margin-right:auto}.float-mark{grid-row:span 2;display:grid;place-items:center;width:32px;height:32px;background:#edf6ef;color:#5a946b;border-radius:10px}.chat-float strong{font-size:13px;color:#48604e}.chat-float small{font-size:10px;color:#97a399}@media(max-width:600px){.chat-float{right:12px;bottom:12px;width:calc(100vw - 24px)}.launcher{right:16px;bottom:16px}}
</style>
