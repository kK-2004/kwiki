<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ElMessage, type ElTree } from "element-plus";
import VChart from "vue-echarts";
import { use } from "echarts/core";
import { CanvasRenderer } from "echarts/renderers";
import { LineChart } from "echarts/charts";
import { DataZoomComponent, GridComponent, LegendComponent, TooltipComponent } from "echarts/components";
import { api, ApiError, type UsageSeriesResponse, type UsageSourceName, type UsageUser } from "../api";
import { useAuth } from "../auth";
import { formatTokens } from "../usageFormat";
import { allLeafKeys, buildLines, buildTree, rangeFor, tableRows, totals, type GroupBy, type Metric, type RangePreset } from "../usageSeries";

use([CanvasRenderer, LineChart, GridComponent, TooltipComponent, LegendComponent, DataZoomComponent]);

const STORE_KEY = "kwiki_admin_model_usage";
const SOURCES: Array<{ value: UsageSourceName; label: string }> = [
  { value: "ONLINE", label: "线上" }, { value: "EVAL", label: "评测" }, { value: "BACKGROUND", label: "后台" }];
const METRICS: Array<{ value: Metric; label: string }> = [
  { value: "total", label: "总 token" }, { value: "input", label: "输入" }, { value: "output", label: "输出" },
  { value: "calls", label: "调用数" }, { value: "failures", label: "失败数" }];

interface Saved { preset: RangePreset | "custom"; sources: UsageSourceName[]; users: number[]; metric: Metric; groupBy: GroupBy; stacked: boolean }

/** 读取本地保存的视图偏好：解析失败整体回退空对象，字段非法逐项回退默认值，避免旧版本脏数据导致页面渲染失败 */
function readSaved(): Partial<Saved> {
  const presets: Array<RangePreset | "custom"> = ["today", "yesterday", "7d", "30d", "custom"];
  try {
    const value = JSON.parse(localStorage.getItem(STORE_KEY) || "{}") as Partial<Saved>;
    return {
      preset: presets.includes(value.preset as RangePreset | "custom") ? value.preset : undefined,
      sources: Array.isArray(value.sources) ? value.sources.filter((s): s is UsageSourceName => SOURCES.some(x => x.value === s)) : undefined,
      users: Array.isArray(value.users) ? value.users.filter((n): n is number => Number.isFinite(n)) : undefined,
      metric: METRICS.some(m => m.value === value.metric) ? value.metric : undefined,
      groupBy: value.groupBy === "step" || value.groupBy === "model" ? value.groupBy : undefined,
      stacked: typeof value.stacked === "boolean" ? value.stacked : undefined,
    };
  } catch {
    return {};
  }
}

const saved = readSaved();

const preset = ref<RangePreset | "custom">(saved.preset ?? "today");
const custom = ref<[Date, Date] | null>(null);
const sources = ref<UsageSourceName[]>(saved.sources ?? SOURCES.map(s => s.value));
const users = ref<number[]>(saved.users ?? []);
const metric = ref<Metric>(saved.metric ?? "total");
const groupBy = ref<GroupBy>(saved.groupBy ?? "step");
const stacked = ref<boolean>(saved.stacked ?? false);
const loading = ref(false);
const data = ref<UsageSeriesResponse | null>(null);
const userOptions = ref<UsageUser[]>([]);
const checked = ref<Set<string>>(new Set());
const treeRef = ref<InstanceType<typeof ElTree>>();
const auth = useAuth();
let timer: ReturnType<typeof setInterval> | undefined;
let requestSeq = 0;

const range = computed(() => preset.value === "custom" && custom.value
  ? { from: custom.value[0], to: custom.value[1] }
  : rangeFor(preset.value === "custom" ? "today" : preset.value));
const tree = computed(() => buildTree(data.value?.series ?? [], groupBy.value));
const lines = computed(() => buildLines(data.value?.series ?? [], tree.value, checked.value, groupBy.value, metric.value));
const summary = computed(() => totals(data.value?.series ?? [], checked.value));
const rows = computed(() => tableRows(buildLines(data.value?.series ?? [], tree.value, checked.value, groupBy.value, "total")));
const isTokenMetric = computed(() => metric.value === "total" || metric.value === "input" || metric.value === "output");

const option = computed(() => ({
  tooltip: { trigger: "axis", valueFormatter: (v: number) => isTokenMetric.value ? formatTokens(v) : String(v) },
  legend: { type: "scroll", top: 0 },
  grid: { left: 56, right: 24, top: 40, bottom: 56 },
  dataZoom: [{ type: "inside" }, { type: "slider", height: 18, bottom: 8 }],
  xAxis: { type: "time" },
  yAxis: { type: "value", axisLabel: { formatter: (v: number) => isTokenMetric.value ? formatTokens(v) : String(v) } },
  series: lines.value.map(line => ({
    name: line.name, type: "line", showSymbol: false, smooth: false,
    stack: stacked.value ? "total" : undefined, areaStyle: stacked.value ? {} : undefined,
    data: (data.value?.buckets ?? []).map((t, i) => [t, line.values[i] ?? 0]),
  })),
}));

function persist() {
  const value: Saved = { preset: preset.value, sources: sources.value, users: users.value, metric: metric.value, groupBy: groupBy.value, stacked: stacked.value };
  localStorage.setItem(STORE_KEY, JSON.stringify(value));
}

function handleError(error: unknown, fallback: string, silent = false) {
  if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
    auth.logout();
    location.assign("/admin/login");
    return;
  }
  // 定时轮询失败只记录日志不弹 toast，避免「今天」视图每 60 秒打扰一次；手动交互仍弹提示
  if (silent) { console.warn("模型用量自动刷新失败", error); return; }
  const code = error instanceof ApiError ? error.code : "";
  const messages: Record<string, string> = {
    "usage-store-unavailable": "用量存储不可用（数据库未连接）", "range-too-long": "时间范围最长 366 天", "invalid-range": "结束时间必须晚于开始时间",
  };
  ElMessage.error(messages[code] ?? fallback);
}

async function load(silent = false) {
  const seq = ++requestSeq;
  const { from, to } = range.value;
  const query = { from: from.toISOString(), to: to.toISOString(), sources: sources.value, users: users.value, tzOffsetMinutes: -new Date().getTimezoneOffset() };
  loading.value = true;
  try {
    const [series, people] = await Promise.all([api.modelUsageSeries(query), api.modelUsageUsers(query)]);
    if (seq !== requestSeq) return; // 已有更新的查询发出，丢弃乱序返回的旧响应
    // 首次加载全选；之后刷新保留用户勾选——仍存在的叶子维持勾选状态，新出现的叶子默认勾选，消失的叶子清除
    const previousLeaves = data.value ? new Set(allLeafKeys(buildTree(data.value.series, groupBy.value))) : new Set<string>();
    data.value = series;
    userOptions.value = people;
    const nextLeaves = allLeafKeys(buildTree(series.series, groupBy.value));
    const leafSet = new Set(nextLeaves);
    checked.value = new Set([...nextLeaves.filter(key => !previousLeaves.has(key)), ...checked.value].filter(key => leafSet.has(key)));
    await nextTick();
    treeRef.value?.setCheckedKeys([...checked.value]);
  } catch (error) {
    if (seq === requestSeq) handleError(error, "加载模型用量失败", silent);
  } finally {
    if (seq === requestSeq) loading.value = false;
  }
}

function onCheck() {
  const keys = (treeRef.value?.getCheckedKeys(true) ?? []) as string[];
  checked.value = new Set(keys);
}

function resetTimer() {
  if (timer) clearInterval(timer);
  // 定时轮询走静默模式：失败不弹 toast，只写控制台日志
  timer = preset.value === "today" ? setInterval(() => void load(true), 60_000) : undefined;
}

watch([preset, custom, sources, users], () => { persist(); resetTimer(); void load(); }, { deep: true });
watch([metric, stacked], persist);
watch(groupBy, async () => {
  persist();
  checked.value = new Set(allLeafKeys(tree.value));
  await nextTick();
  treeRef.value?.setCheckedKeys([...checked.value]);
});

onMounted(() => { resetTimer(); void load(); });
onBeforeUnmount(() => { if (timer) clearInterval(timer); });
</script>

<template>
  <div class="usage-page">
    <header class="page-header">
      <h1>模型用量</h1>
      <p class="hint">统计对话模型与 embedding 的 token 消耗；数据每 10 秒落库一次，当天视图每分钟自动刷新。</p>
    </header>

    <section class="controls" v-loading="loading">
      <el-radio-group v-model="preset" aria-label="时间范围">
        <el-radio-button value="today">今天</el-radio-button>
        <el-radio-button value="yesterday">昨天</el-radio-button>
        <el-radio-button value="7d">近 7 天</el-radio-button>
        <el-radio-button value="30d">近 30 天</el-radio-button>
        <el-radio-button value="custom">自定义</el-radio-button>
      </el-radio-group>
      <el-date-picker v-if="preset === 'custom'" v-model="custom" type="datetimerange" range-separator="至"
                      start-placeholder="开始" end-placeholder="结束" aria-label="自定义时间" />
      <el-checkbox-group v-model="sources" aria-label="来源">
        <el-checkbox v-for="s in SOURCES" :key="s.value" :value="s.value">{{ s.label }}</el-checkbox>
      </el-checkbox-group>
      <el-select v-model="users" multiple filterable collapse-tags clearable placeholder="全部用户" style="width: 220px" aria-label="用户">
        <el-option v-for="u in userOptions" :key="u.userId" :value="u.userId" :label="`${u.displayName}（${formatTokens(u.totalTokens)}）`" />
      </el-select>
      <el-select v-model="metric" style="width: 120px" aria-label="指标">
        <el-option v-for="m in METRICS" :key="m.value" :value="m.value" :label="m.label" />
      </el-select>
      <el-switch v-model="stacked" active-text="堆叠" />
    </section>

    <section class="cards">
      <div class="card"><span>总 token</span><strong data-testid="total-tokens">{{ formatTokens(summary.total) }}</strong></div>
      <div class="card"><span>输入</span><strong>{{ formatTokens(summary.input) }}</strong></div>
      <div class="card"><span>输出</span><strong>{{ formatTokens(summary.output) }}</strong></div>
      <div class="card"><span>调用数</span><strong>{{ formatTokens(summary.calls) }}</strong></div>
      <div class="card"><span>失败率</span><strong>{{ (summary.failureRate * 100).toFixed(1) }}%</strong></div>
      <div class="card"><span>平均耗时</span><strong>{{ summary.avgLatencyMs }} ms</strong></div>
    </section>

    <section class="body">
      <aside class="dimension">
        <el-radio-group v-model="groupBy" size="small" aria-label="维度">
          <el-radio-button value="step">按步骤</el-radio-button>
          <el-radio-button value="model">按模型</el-radio-button>
        </el-radio-group>
        <el-tree ref="treeRef" :data="tree" node-key="id" show-checkbox default-expand-all
                 :props="{ label: 'label', children: 'children' }" @check="onCheck" />
        <p v-if="!tree.length" class="hint">所选范围内没有调用</p>
      </aside>
      <div class="chart-area">
        <v-chart class="chart" :option="option" autoresize />
        <el-table :data="rows" size="small" :default-sort="{ prop: 'total', order: 'descending' }">
          <el-table-column prop="name" label="维度" min-width="220" />
          <el-table-column prop="total" label="总 token" sortable><template #default="{ row }">{{ formatTokens(row.total) }}</template></el-table-column>
          <el-table-column prop="input" label="输入" sortable><template #default="{ row }">{{ formatTokens(row.input) }}</template></el-table-column>
          <el-table-column prop="output" label="输出" sortable><template #default="{ row }">{{ formatTokens(row.output) }}</template></el-table-column>
          <el-table-column prop="calls" label="调用数" sortable />
          <el-table-column prop="failures" label="失败数" sortable />
          <el-table-column prop="avgLatencyMs" label="平均耗时(ms)" sortable />
        </el-table>
      </div>
    </section>
  </div>
</template>

<style scoped>
/* 与现有页面一致：颜色、圆角、间距全部来自 --k-* 令牌 */
.usage-page {
  max-width: 1400px;
  margin: 0 auto;
  padding: 32px 40px 64px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.page-header {
  margin-bottom: 8px;
  padding-bottom: 20px;
  border-bottom: 1px solid var(--k-line);
}

.page-header h1 {
  margin: 0 0 6px;
  font-size: 26px;
  font-weight: 600;
  letter-spacing: -0.5px;
  color: var(--k-ink);
}

.controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}

.cards {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 0;
}

.card {
  padding: 14px 16px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r-lg);
  background: var(--k-canvas);
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.card span {
  color: var(--k-muted);
  font-size: 12px;
  font-weight: 500;
}

.card strong {
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.3px;
  color: var(--k-ink);
  font-variant-numeric: tabular-nums;
}

.body {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  gap: 16px;
}

.dimension {
  border: 1px solid var(--k-line);
  border-radius: var(--k-r-lg);
  background: var(--k-canvas);
  padding: 18px 20px;
  max-height: 640px;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.chart-area {
  border: 1px solid var(--k-line);
  border-radius: var(--k-r-lg);
  background: var(--k-canvas);
  padding: 18px 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

.chart {
  height: 380px;
}

@media (max-width: 900px) {
  .usage-page {
    padding: 16px;
  }

  .cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .body {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
