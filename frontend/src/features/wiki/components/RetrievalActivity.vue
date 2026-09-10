<script setup lang="ts">
/**
 * 两套回答界面（会话
 * 页面与侧边面板）共用的检索活动（retrieval-activity）折叠区。收起时：一行绿色状态，含真实
 * 步骤数与耗时，以及完成标记。展开时：一条细而低对比度的
 * 时间线，按 queryRound → attemptStage 分组，展示真实阶段 ——
 * 路由、两条召回分支 + RRF TopK、候选生成、QA、父级
 * 补充、扩展、改写 —— 仅展示真实指标；从不
 * 编造数字。可键盘操作（按钮 + aria-expanded），状态不
 * 仅靠颜色传达，且手动展开状态按各实例分别保存。
 */
import { computed, ref, watch } from 'vue';
import type { ActivityStep } from '../sse';

const props = defineProps<{
  steps: ActivityStep[];
  running: boolean;
  failed: boolean;
  /** 该消息持久化的手动展开偏好。 */
  expandedInitial?: boolean;
}>();

const emit = defineEmits<{ (e: 'update:expanded', value: boolean): void }>();

const expanded = ref(props.expandedInitial ?? false);

watch(
  () => props.expandedInitial,
  (value) => {
    if (value != null) expanded.value = value;
  },
);

function toggle() {
  expanded.value = !expanded.value;
  emit('update:expanded', expanded.value);
}

const completedSteps = computed(() =>
  props.steps.filter((step) => step.status === 'COMPLETED' || step.status === 'SKIPPED').length,
);

const totalDurationMs = computed(() =>
  props.steps.reduce((sum, step) => sum + (step.durationMs ?? 0), 0),
);

const hasRetrieval = computed(() =>
  props.steps.some((step) => step.phase === 'RETRIEVAL'),
);

const overallStatus = computed(() => {
  if (props.failed) return '检索失败';
  if (props.running) return '正在检索知识库';
  if (!hasRetrieval.value && props.steps.length === 0) return '';
  if (!props.running && props.steps.length > 0) return '已完成知识检索';
  return '正在检索知识库';
});

const markerLabel = computed(() => {
  if (props.failed) return '失败';
  if (props.running) return '进行中';
  return '完成';
});

interface RoundGroup {
  round: number;
  stages: { stage: string; label: string; steps: ActivityStep[] }[];
}

const STAGE_LABELS: Record<string, string> = {
  BASE_CHILD: '基础检索',
  BASE_PARENT: '补充上下文',
  EXPANDED_CHILD: '扩大检索',
  EXPANDED_PARENT: '扩大后补充上下文',
};

const PHASE_LABELS: Record<string, string> = {
  ROUTE: '理解问题',
  RETRIEVAL: '双路检索',
  GENERATION: '生成候选',
  QUALITY: '质量评审',
  PARENT_FETCH: '补充完整段落',
  EXPANSION: '扩大检索范围',
  REWRITE: '改写检索问题',
  FINAL: '结束',
};

const grouped = computed<RoundGroup[]>(() => {
  const rounds = new Map<number, RoundGroup>();
  for (const step of props.steps) {
    const round = step.queryRound || 1;
    if (!rounds.has(round)) rounds.set(round, { round, stages: [] });
    const group = rounds.get(round)!;
    const stageKey = step.attemptStage ?? 'GLOBAL';
    let stage = group.stages.find((entry) => entry.stage === stageKey);
    if (!stage) {
      stage = { stage: stageKey, label: STAGE_LABELS[stageKey] ?? '处理', steps: [] };
      group.stages.push(stage);
    }
    stage.steps.push(step);
  }
  return [...rounds.values()];
});

function stepStatusLabel(step: ActivityStep): string {
  switch (step.status) {
    case 'COMPLETED': return '完成';
    case 'SKIPPED': return '跳过';
    case 'FAILED': return '失败';
    default: return '进行中';
  }
}

function durationText(step: ActivityStep): string {
  if (step.durationMs == null) return '';
  if (step.durationMs < 1000) return `${step.durationMs}ms`;
  return `${(step.durationMs / 1000).toFixed(1)}s`;
}

function sourceTitles(step: ActivityStep): string[] {
  const titles = step.metrics?.sourceTitles;
  return Array.isArray(titles) ? titles.filter((t): t is string => typeof t === 'string') : [];
}

function metricNumber(step: ActivityStep, key: string): number | undefined {
  const value = step.metrics?.[key];
  return typeof value === 'number' ? value : undefined;
}

function degraded(step: ActivityStep): boolean {
  const branches = step.metrics?.degradedBranches;
  return Array.isArray(branches) && branches.length > 0;
}
</script>

<template>
  <div v-if="overallStatus" class="retrieval-activity" data-testid="retrieval-activity">
    <button
      type="button"
      class="summary-row"
      :aria-expanded="expanded"
      aria-controls="retrieval-timeline"
      data-testid="retrieval-activity-toggle"
      @click="toggle"
      @keydown.enter.prevent="toggle"
      @keydown.space.prevent="toggle"
    >
      <span class="icon i-lucide-sparkles" aria-hidden="true"></span>
      <span class="status">{{ overallStatus }}</span>
      <span v-if="completedSteps > 0" class="meta">· {{ completedSteps }} 个步骤</span>
      <span v-if="totalDurationMs > 0" class="meta optional">· {{ durationText({ durationMs: totalDurationMs } as ActivityStep) }}</span>
      <span class="marker" :class="{ failed, running }" role="img" :aria-label="markerLabel">
        <span v-if="failed" class="i-lucide-circle-x" aria-hidden="true"></span>
        <span v-else-if="running" class="i-lucide-loader-circle marker-spinner" aria-hidden="true"></span>
        <span v-else class="i-lucide-circle-check" aria-hidden="true"></span>
      </span>
      <span class="chevron i-lucide-chevron-right" :class="{ open: expanded }" aria-hidden="true"></span>
    </button>
    <ol v-if="expanded" id="retrieval-timeline" class="timeline" data-testid="retrieval-timeline">
      <li v-for="round in grouped" :key="round.round" class="round">
        <div v-if="grouped.length > 1" class="round-title">第 {{ round.round }} 轮检索</div>
        <div v-for="stage in round.stages" :key="stage.stage" class="stage">
          <div v-if="stage.label !== '处理' || round.stages.length > 1" class="stage-title">{{ stage.label }}</div>
          <ol class="steps">
            <li v-for="step in stage.steps" :key="step.stepId" class="step" :class="`status-${step.status.toLowerCase()}`">
              <span class="dot" aria-hidden="true">
                <span v-if="step.status === 'COMPLETED'" class="i-lucide-check"></span>
                <span v-else-if="step.status === 'SKIPPED'" class="i-lucide-minus"></span>
                <span v-else-if="step.status === 'FAILED'" class="i-lucide-x"></span>
                <span v-else class="i-lucide-loader-circle marker-spinner"></span>
              </span>
              <div class="body">
                <div class="line">
                  <span class="phase">{{ PHASE_LABELS[step.phase] ?? step.phase }}</span>
                  <span class="summary">{{ step.summary ?? '' }}</span>
                </div>
                <div v-if="step.reasonCode" class="reason">{{ step.reasonCode }}</div>
                <div class="facts">
                  <span v-if="durationText(step)" class="fact">{{ durationText(step) }}</span>
                  <span v-if="metricNumber(step, 'retainedChildCount') != null" class="fact">保留 {{ metricNumber(step, 'retainedChildCount') }} 个片段</span>
                  <span v-if="metricNumber(step, 'fusedCandidateCount') != null" class="fact optional">融合候选 {{ metricNumber(step, 'fusedCandidateCount') }}</span>
                  <span v-if="metricNumber(step, 'parentCount') != null" class="fact">父段落 {{ metricNumber(step, 'parentCount') }}</span>
                  <span v-if="degraded(step)" class="fact degraded">部分检索链路降级</span>
                  <span class="fact state">{{ stepStatusLabel(step) }}</span>
                </div>
                <div v-if="sourceTitles(step).length" class="sources">
                  <span v-for="title in sourceTitles(step)" :key="title" class="source" :title="title">{{ title }}</span>
                </div>
              </div>
            </li>
          </ol>
        </div>
      </li>
    </ol>
  </div>
</template>

<style scoped>
.retrieval-activity {
  border: 1px solid #e7eae9;
  border-radius: 8px;
  background: #fbfcfc;
  font-size: 12px;
  color: #252a2a;
}
.summary-row {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 6px 10px;
  background: none;
  border: none;
  cursor: pointer;
  color: inherit;
  font: inherit;
  text-align: left;
}
.summary-row:focus-visible {
  outline: 2px solid #18bc72;
  outline-offset: 1px;
}
.icon {
  color: #18bc72;
  font-size: 13px;
}
.status {
  font-weight: 500;
}
.meta {
  color: #858c8c;
}
.marker.failed { color: #ad4c4c; }
.marker.running { color: #8a6c42; }
.marker:not(.failed):not(.running) { color: #0e9858; }
.marker {
  display: inline-flex;
  font-size: 13px;
}
.marker-spinner { animation: marker-spin 0.9s linear infinite; }
@keyframes marker-spin { to { transform: rotate(360deg); } }
.chevron {
  margin-left: auto;
  color: #858c8c;
  transition: transform 0.15s ease;
}
.chevron.open { transform: rotate(90deg); }
.timeline {
  list-style: none;
  margin: 0;
  padding: 4px 10px 10px 14px;
  border-top: 1px dashed #e7eae9;
}
.round + .round { margin-top: 8px; }
.round-title {
  color: #858c8c;
  margin: 6px 0 2px;
}
.stage-title {
  color: #858c8c;
  margin: 6px 0 2px;
}
.steps {
  list-style: none;
  margin: 0;
  padding: 0;
  border-left: 1px solid #e7eae9;
}
.step {
  position: relative;
  display: flex;
  gap: 8px;
  padding: 4px 0 4px 10px;
}
.dot {
  position: absolute;
  left: -7px;
  top: 6px;
  width: 13px;
  height: 13px;
  border-radius: 50%;
  background: #fff;
  border: 1px solid #e7eae9;
  color: #858c8c;
  font-size: 9px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.step.status-completed .dot { color: #0e9858; border-color: #bfe8d5; }
.step.status-failed .dot { color: #ad4c4c; border-color: #e7c4c4; }
.line { display: flex; gap: 6px; flex-wrap: wrap; align-items: baseline; }
.phase { color: #858c8c; }
.summary { color: #252a2a; }
.reason { color: #8a6c42; margin-top: 1px; }
.facts {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  color: #858c8c;
  margin-top: 2px;
}
.fact.state { color: #252a2a; }
.fact.degraded { color: #8a6c42; }
.sources {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 2px;
}
.source {
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  background: #eaf8f1;
  color: #0e9858;
  border-radius: 4px;
  padding: 1px 6px;
}
@media (max-width: 640px) {
  .meta.optional, .fact.optional { display: none; }
  .source { max-width: 140px; }
}
</style>
