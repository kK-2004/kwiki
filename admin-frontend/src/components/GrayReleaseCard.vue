<script setup lang="ts">
import { computed } from "vue";
import { ElMessageBox } from "element-plus";
import type { GrayRelease } from "../api";

const props = defineProps<{ release: GrayRelease; busy: boolean }>();
const emit = defineEmits<{ command: [action: "sync" | "switch" | "switch-back" | "end"] }>();

const STATUS: Record<GrayRelease["status"], { text: string; type: "info" | "warning" | "success" | "primary" | "danger" }> = {
  CREATED: { text: "待同步", type: "info" },
  SYNCING: { text: "同步中", type: "warning" },
  SYNCED: { text: "已同步，未切换", type: "primary" },
  SWITCHED: { text: "已切换到灰度", type: "success" },
  ENDED: { text: "已结束", type: "info" },
};
const status = computed(() => STATUS[props.release.status]);
const progressText = computed(() => {
  const p = props.release.progress;
  if (!p.runId) return "尚未同步";
  return `已扫描 ${p.scanned} · 成功 ${p.succeeded} · 失败 ${p.failed} · 双写积压 ${p.pendingTargets}`;
});

const CONFIRM: Record<"switch" | "switch-back" | "end", string> = {
  switch: "这些知识库的检索将立即改为读取灰度索引，旧索引继续双写保鲜，可随时切回。",
  "switch-back": "这些知识库的检索将立即改回读取全局索引，灰度索引继续双写，可再次切换。",
  end: "这些知识库将回到全局索引，并停止向灰度索引写入。灰度索引会保留，需要时在「全局索引」页手动删除。",
};

async function run(action: "sync" | "switch" | "switch-back" | "end") {
  if (action !== "sync") {
    try { await ElMessageBox.confirm(CONFIRM[action], "确认操作", { type: "warning" }); } catch { return; }
  }
  emit("command", action);
}
</script>

<template>
  <article class="gray-card">
    <header>
      <div>
        <h3>{{ release.name }}</h3>
        <p class="meta">解析器 <strong>{{ release.parserLabel }}</strong> · 索引 <code>{{ release.physicalName }}</code> · {{ release.createdBy }}</p>
      </div>
      <el-tag :type="status.type" effect="light">{{ status.text }}</el-tag>
    </header>
    <div class="kbs"><el-tag v-for="kb in release.kbs" :key="kb.kbId" effect="plain" size="small">{{ kb.name }}</el-tag></div>
    <p class="progress">{{ progressText }}</p>
    <el-alert v-if="release.lastError" :title="release.lastError" type="error" :closable="false" show-icon />
    <footer>
      <el-button v-if="release.allowedActions.sync" :loading="busy" @click="run('sync')">开始同步</el-button>
      <el-button v-if="release.allowedActions.switch" type="primary" :loading="busy" @click="run('switch')">切换到灰度索引</el-button>
      <el-button v-if="release.allowedActions.switchBack" :loading="busy" @click="run('switch-back')">切回原索引</el-button>
      <el-button v-if="release.allowedActions.end" text type="danger" :loading="busy" @click="run('end')">结束灰度</el-button>
    </footer>
  </article>
</template>

<style scoped>
.gray-card { display: grid; gap: 12px; padding: 18px 20px; border: 1px solid var(--k-line); border-radius: var(--k-r-lg); background: var(--k-canvas); }
header { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; }
h3 { margin: 0; font-size: 16px; font-weight: 600; letter-spacing: -0.2px; }
.meta { margin: 4px 0 0; color: var(--k-muted); font-size: 13px; }
.meta strong { color: var(--k-ink); }
code { font-family: var(--k-font-mono); font-size: 12px; }
.kbs { display: flex; flex-wrap: wrap; gap: 6px; }
.progress { margin: 0; color: var(--k-ink-2); font-size: 13px; font-variant-numeric: tabular-nums; }
footer { display: flex; gap: 8px; flex-wrap: wrap; }
</style>
