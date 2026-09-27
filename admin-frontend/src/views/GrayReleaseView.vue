<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { grayErrorMessage } from "../grayErrors";
import { api, ApiError, type GrayRelease } from "../api";
import { useAuth } from "../auth";
import GrayReleaseCard from "../components/GrayReleaseCard.vue";
import GrayReleaseCreateDialog from "../components/GrayReleaseCreateDialog.vue";

const auth = useAuth();
const releases = ref<GrayRelease[]>([]);
const loading = ref(false);
const creating = ref(false);
/** 正在执行操作的灰度 id；整体替换 Set 以触发响应式更新 */
const busyIds = ref(new Set<number>());
const showEnded = ref(false);
let timer: number | undefined;
let pollDelay = 15000;
let inFlight = false;
let disposed = false;

const active = computed(() => releases.value.filter(release => release.status !== "ENDED"));
const ended = computed(() => releases.value.filter(release => release.status === "ENDED"));
/** 已在未结束灰度中的知识库 → 灰度名称，供新建弹窗置灰 */
const occupied = computed(() => Object.fromEntries(active.value.flatMap(release => release.kbs.map(kb => [kb.kbId, release.name]))));

async function load(silent = false) {
  if (!silent) loading.value = true;
  inFlight = true;
  try {
    releases.value = await api.grayReleases();
    // 成功后恢复正常节奏：有同步中的灰度时 3 秒，否则 15 秒
    pollDelay = releases.value.some(release => release.status === "SYNCING") ? 3000 : 15000;
  } catch (error) {
    if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
      auth.logout();
      location.assign("/admin/login");
    } else if (!silent) {
      ElMessage.error("灰度列表加载失败");
    }
    // 失败时退避：间隔翻倍，上限 30 秒
    pollDelay = Math.min(pollDelay * 2, 30000);
  } finally {
    inFlight = false;
    loading.value = false;
    schedule();
  }
}

/** 页面卸载后不再续期；定时触发时若已有请求在途则跳过，由在途请求结束后重新调度 */
function schedule() {
  window.clearTimeout(timer);
  if (disposed) return;
  timer = window.setTimeout(() => { if (!inFlight) void load(true); }, pollDelay);
}

function setBusy(id: number, busy: boolean) {
  const next = new Set(busyIds.value);
  if (busy) next.add(id); else next.delete(id);
  busyIds.value = next;
}

async function command(release: GrayRelease, action: "sync" | "switch" | "switch-back" | "end") {
  setBusy(release.id, true);
  try {
    await api.grayCommand(release.id, action);
    window.clearTimeout(timer);
    await load(true);
  } catch (error) {
    ElMessage.error(grayErrorMessage(error, "操作失败"));
  } finally {
    setBusy(release.id, false);
  }
}

onMounted(() => load());
onBeforeUnmount(() => { disposed = true; window.clearTimeout(timer); });
</script>

<template>
  <main class="page" v-loading="loading">
    <section class="hero">
      <div>
        <h1>灰度发布</h1>
        <p class="muted">选择部分知识库试用新的解析器：系统会为它们建立独立的灰度索引。同步完成后由你手动切换，旧索引持续双写保鲜，可随时切回。</p>
      </div>
      <el-button type="primary" @click="creating = true">新建灰度</el-button>
    </section>
    <el-empty v-if="!loading && !active.length" description="暂无进行中的灰度" />
    <div class="grid"><GrayReleaseCard v-for="release in active" :key="release.id" :release="release" :busy="busyIds.has(release.id)" @command="command(release, $event)" /></div>
    <section v-if="ended.length" class="ended">
      <el-button text @click="showEnded = !showEnded">{{ showEnded ? "收起" : "查看" }}已结束的灰度（{{ ended.length }}）</el-button>
      <div v-if="showEnded" class="grid"><GrayReleaseCard v-for="release in ended" :key="release.id" :release="release" :busy="false" /></div>
    </section>
    <GrayReleaseCreateDialog v-model="creating" :occupied="occupied" @created="load(true)" />
  </main>
</template>

<style scoped>
.grid { display: grid; gap: 14px; }
.ended { margin-top: 24px; }
</style>
