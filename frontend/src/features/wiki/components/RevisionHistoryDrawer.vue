<template>
  <aside class="history open" role="dialog" aria-label="版本历史" data-testid="revision-history">
    <div class="drawer-head">
      <h2>版本历史</h2>
      <button type="button" class="icon" aria-label="关闭版本历史" @click="emit('close')">
        <i class="i-lucide-x" aria-hidden="true"></i>
      </button>
    </div>
    <ol class="revisions">
      <li
        v-for="(revision, index) in revisions"
        :key="revision.revisionNo"
        class="revision"
        :class="{ current: index === 0 }"
        data-testid="revision-item"
      >
        <strong>r{{ revision.revisionNo }} · {{ index === 0 ? '当前版本' : '已发布' }}</strong>
        <div class="rev-meta">{{ revision.createdAt }}</div>
        <p>{{ revision.changeNote ?? '无说明' }}</p>
        <button type="button" class="btn" @click="selected = revision">查看版本</button>
      </li>
    </ol>
    <p v-if="revisions.length === 0" class="empty" data-testid="revision-empty">暂无历史版本</p>
    <div v-if="selected" class="revision-overlay" @click.self="selected = null">
      <section class="revision-dialog" role="dialog" aria-modal="true" :aria-label="`版本 r${selected.revisionNo}`">
        <header><div><h3>r{{ selected.revisionNo }}</h3><p>{{ selected.changeNote || '无说明' }}</p></div><button type="button" class="icon" aria-label="关闭版本内容" @click="selected = null"><i class="i-lucide-x" /></button></header>
        <div class="revision-content markdown" v-html="renderMarkdown(selected.markdown || '')"></div>
        <footer><button type="button" class="btn" @click="selected = null">关闭</button><button v-if="selected.revisionNo !== revisions[0]?.revisionNo" type="button" class="btn primary" @click="restoreSelected">恢复到当前版本</button></footer>
      </section>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { renderMarkdown } from './render';
const props = defineProps<{
  revisions: Array<{ revisionNo: number; changeNote?: string; createdAt: string; markdown?: string }>;
}>();
const emit = defineEmits<{ restore: [revisionNo: number]; close: [] }>();
const selected = ref<(typeof props.revisions)[number] | null>(null);
function restoreSelected() { if (!selected.value) return; emit('restore', selected.value.revisionNo); selected.value = null; }
</script>

<style scoped>
.history {
  position: fixed;
  top: 0;
  right: 0;
  z-index: 50;
  width: min(390px, 92vw);
  height: 100vh;
  padding: 20px;
  overflow: auto;
  border-left: 1px solid var(--kwiki-line);
  background: var(--kwiki-panel);
  box-shadow: -18px 0 48px rgba(24, 40, 32, 0.11);
  transform: translateX(0);
}
.drawer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 19px;
}
.drawer-head h2 {
  margin: 0;
  font-size: 18px;
}
.icon {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border: 0;
  background: none;
  border-radius: 7px;
  color: #737a7a;
  cursor: pointer;
  font-size: 16px;
}
.icon:hover {
  background: #eef1ef;
  color: #303636;
}
.revisions {
  list-style: none;
  margin: 0;
  padding: 0;
}
.revision {
  position: relative;
  margin-left: 9px;
  padding: 0 0 20px 24px;
  border-left: 1px solid #dfe4e2;
}
.revision::before {
  content: '';
  position: absolute;
  left: -5px;
  top: 3px;
  width: 9px;
  height: 9px;
  border: 2px solid #bdc6c2;
  border-radius: 50%;
  background: var(--kwiki-panel);
}
.revision.current::before {
  border-color: var(--kwiki-green);
  background: var(--kwiki-green-soft);
}
.revision p {
  margin: 4px 0 10px;
  color: var(--kwiki-muted);
  line-height: 1.55;
}
.rev-meta {
  color: #a0a6a6;
  font-size: 11px;
}
.btn {
  min-height: 34px;
  padding: 0 13px;
  border: 1px solid #dfe3e2;
  border-radius: 6px;
  background: var(--kwiki-panel);
  font-weight: 600;
  color: #555c5c;
  cursor: pointer;
  font: inherit;
}
.btn:hover {
  border-color: #bfc7c4;
}
.empty {
  color: var(--kwiki-muted);
}
.revision-overlay { position: fixed; inset: 0; z-index: 70; display: grid; place-items: center; padding: 24px; background: #19332255; }
.revision-dialog { width: min(860px, 100%); max-height: min(760px, 90vh); display: flex; flex-direction: column; overflow: hidden; border-radius: 16px; background: #fff; box-shadow: 0 24px 80px #1a332633; }
.revision-dialog header,.revision-dialog footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 18px 22px; border-bottom: 1px solid var(--kwiki-line); }
.revision-dialog header h3,.revision-dialog header p { margin: 0; }
.revision-dialog header p { margin-top: 5px; color: var(--kwiki-muted); font-size: 12px; }
.revision-content { flex: 1; min-height: 240px; overflow: auto; padding: 24px; }
.revision-dialog footer { justify-content: flex-end; border-top: 1px solid var(--kwiki-line); border-bottom: 0; }
.btn.primary { border-color: var(--kwiki-green); background: var(--kwiki-green); color: #fff; }
</style>
