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
        <button type="button" class="btn" @click="emit('restore', revision.revisionNo)">
          {{ index === 0 ? '与上一版比较' : '恢复为新版本' }}
        </button>
      </li>
    </ol>
    <p v-if="revisions.length === 0" class="empty" data-testid="revision-empty">暂无历史版本</p>
  </aside>
</template>

<script setup lang="ts">
defineProps<{
  revisions: Array<{ revisionNo: number; changeNote?: string; createdAt: string }>;
}>();
const emit = defineEmits<{ restore: [revisionNo: number]; close: [] }>();
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
</style>
