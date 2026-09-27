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
        <strong>v{{ revision.revisionNo }} · {{ index === 0 ? '当前版本' : '已发布' }}</strong>
        <div class="rev-meta">{{ revision.createdAt }}</div>
        <p>{{ revision.changeNote ?? '无说明' }}</p>
        <button type="button" class="btn" @click="selected = revision">查看版本</button>
      </li>
    </ol>
    <p v-if="revisions.length === 0" class="empty" data-testid="revision-empty">暂无历史版本</p>
    <div v-if="selected" class="revision-overlay" @click.self="selected = null">
      <section class="revision-dialog" role="dialog" aria-modal="true" :aria-label="`版本 v${selected.revisionNo}`">
        <header><div><h3>v{{ selected.revisionNo }}</h3><p>{{ selected.changeNote || '无说明' }}</p></div><button type="button" class="icon" aria-label="关闭版本内容" @click="selected = null"><i class="i-lucide-x" /></button></header>
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
  top: 12px;
  right: 12px;
  bottom: 12px;
  z-index: 50;
  width: min(390px, 92vw);
  padding: 20px;
  overflow: auto;
  /* 抽屉：悬浮卡片样式，与弹窗统一 */
  border: 1px solid var(--k-line);
  border-radius: var(--k-r-lg);
  background: var(--k-canvas);
  box-shadow: var(--k-shadow-float);
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
  font-size: 16px;
  font-weight: 600;
  color: var(--k-ink);
}
.icon {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border: 0;
  background: none;
  border-radius: var(--k-r-sm);
  color: var(--k-muted);
  cursor: pointer;
  font-size: 16px;
}
.icon:hover {
  background: var(--k-surface-hover);
  color: var(--k-ink);
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
  border-left: 1px solid var(--k-line);
}
.revision::before {
  content: '';
  position: absolute;
  left: -5px;
  top: 3px;
  width: 9px;
  height: 9px;
  border: 2px solid var(--k-line-strong);
  border-radius: 50%;
  background: var(--k-canvas);
}
.revision.current::before {
  border-color: var(--k-green);
  background: var(--k-green-soft);
}
.revision p {
  margin: 4px 0 10px;
  color: var(--k-muted);
  line-height: 1.55;
}
.revision strong {
  color: var(--k-ink);
  font-weight: 600;
}
.rev-meta {
  color: var(--k-faint);
  font-size: 11px;
}
.btn {
  min-height: 34px;
  padding: 0 13px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r-sm);
  background: var(--k-canvas);
  font-weight: 500;
  color: var(--k-ink-2);
  cursor: pointer;
  font: inherit;
}
.btn:hover {
  border-color: var(--k-line-strong);
  background: var(--k-surface);
  color: var(--k-ink);
}
.empty {
  color: var(--k-muted);
}
.revision-overlay { position: fixed; inset: 0; z-index: 70; display: grid; place-items: center; padding: 24px; background: var(--k-overlay); }
.revision-dialog { width: min(860px, 100%); max-height: min(760px, 90vh); display: flex; flex-direction: column; overflow: hidden; border: 1px solid var(--k-line); border-radius: var(--k-r-lg); background: var(--k-canvas); box-shadow: var(--k-shadow-float); }
.revision-dialog header,.revision-dialog footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 18px 22px; border-bottom: 1px solid var(--k-line); }
.revision-dialog header h3,.revision-dialog header p { margin: 0; }
.revision-dialog header p { margin-top: 5px; color: var(--k-muted); font-size: 12px; }
.revision-content { flex: 1; min-height: 240px; overflow: auto; padding: 24px; }
.revision-dialog footer { justify-content: flex-end; border-top: 1px solid var(--k-line); border-bottom: 0; }
.btn.primary { border-color: var(--k-primary); background: var(--k-primary); color: var(--k-on-primary); }
.btn.primary:hover { border-color: var(--k-primary-hover); background: var(--k-primary-hover); color: var(--k-on-primary); }
</style>
