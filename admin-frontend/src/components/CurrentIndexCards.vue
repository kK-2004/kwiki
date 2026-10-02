<script setup lang="ts">
import { computed, ref } from "vue";
import type { Version } from "../api";
import { parserLabel } from "../parsers";

const props = defineProps<{ versions: Version[]; aliasTargets: string[] }>();
/** 只负责挑选目标版本；真正的切换由页面现有的输入确认流程完成，避免出现第二条绕过确认的切换路径 */
const emit = defineEmits<{ select: [version: Version] }>();

const current = computed(() => props.versions.find(version => version.selected));
/** 可发布为全局的版本：服务端允许 select 且不是灰度版本 */
const candidates = computed(() => props.versions.filter(version => version.allowedActions.select && !version.kbScoped));
const open = ref(false);
const target = ref<number | null>(null);

function openDialog() {
  target.value = candidates.value[0]?.versionNumber ?? null;
  open.value = true;
}

function next() {
  const chosen = candidates.value.find(version => version.versionNumber === target.value);
  if (!chosen) return;
  open.value = false;
  emit("select", chosen);
}
</script>

<template>
  <section class="current-cards">
    <!-- 解析器随索引版本一起切换，别名与解析器是同一次选择，故合为一张卡片、一个切换入口 -->
    <button type="button" class="current-card" aria-label="当前线上索引，点击切换" @click="openDialog">
      <span class="action">切换</span>
      <span class="field">
        <span class="label">当前别名</span>
        <strong>{{ aliasTargets.join(", ") || "未指向任何索引" }}</strong>
        <span class="sub">kwiki-chunks → 线上读请求实际命中的物理索引</span>
      </span>
      <span class="field">
        <span class="label">当前解析器</span>
        <strong>{{ current ? parserLabel(current.configuration.parserVersion) : "—" }}</strong>
        <span class="sub">{{ current?.configuration.parserVersion }} · 新导入的 PDF 与全局索引使用该解析器</span>
      </span>
    </button>
    <el-dialog v-model="open" title="切换全局索引" width="560">
      <p class="note">解析器随索引版本一起切换：选择一个已同步、已校验的版本，线上检索与新导入都会改用它。只想让部分知识库试用新解析器，请使用「灰度发布」。</p>
      <el-radio-group v-if="candidates.length" v-model="target" class="choices">
        <el-radio v-for="version in candidates" :key="version.versionNumber" :value="version.versionNumber" border>
          {{ version.physicalName }} · {{ parserLabel(version.configuration.parserVersion) }}
        </el-radio>
      </el-radio-group>
      <el-empty v-else description="暂无可发布的版本：请先在下方新建版本并完成开启双写、存量迁移与校验" />
      <template #footer>
        <el-button @click="open = false">取消</el-button>
        <el-button type="primary" :disabled="!target" @click="next">下一步</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.current-cards { margin-bottom: 24px; }
.current-card { position: relative; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 24px; width: 100%; padding: 18px 20px; border: 1px solid var(--k-line); border-radius: var(--k-r-lg); background: var(--k-canvas); color: var(--k-ink); font: inherit; text-align: left; cursor: pointer; transition: border-color var(--k-ease), box-shadow var(--k-ease); }
.current-card:hover { border-color: var(--k-line-strong); box-shadow: var(--k-shadow); }
.field { display: grid; gap: 6px; align-content: start; }
.label { color: var(--k-muted); font-size: 12px; font-weight: 500; }
strong { font-family: var(--k-font-mono); font-size: 22px; font-weight: 600; letter-spacing: -0.4px; }
.sub { color: var(--k-muted); font-size: 12px; }
.action { position: absolute; top: 16px; right: 18px; color: var(--k-green-deep); font-size: 13px; font-weight: 500; }
.note { margin: 0 0 14px; color: var(--k-ink-2); line-height: 1.6; }
.choices { display: grid; gap: 8px; }
@media (max-width: 900px) { .current-card { grid-template-columns: 1fr; } }
</style>
