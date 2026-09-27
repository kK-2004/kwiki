<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { grayErrorMessage } from "../grayErrors";
import { api, type GrayRelease } from "../api";
import GrayReleaseCard from "../components/GrayReleaseCard.vue";
import GrayReleaseCreateDialog from "../components/GrayReleaseCreateDialog.vue";

const releases = ref<GrayRelease[]>([]);
const loading = ref(false);
const creating = ref(false);
const busyId = ref<number | null>(null);
const showEnded = ref(false);
let timer: number | undefined;

const active = computed(() => releases.value.filter(release => release.status !== "ENDED"));
const ended = computed(() => releases.value.filter(release => release.status === "ENDED"));
/** 已在未结束灰度中的知识库 → 灰度名称，供新建弹窗置灰 */
const occupied = computed(() => Object.fromEntries(active.value.flatMap(release => release.kbs.map(kb => [kb.kbId, release.name]))));

async function load(silent = false) {
  if (!silent) loading.value = true;
  try { releases.value = await api.grayReleases(); } catch { if (!silent) ElMessage.error("灰度列表加载失败"); }
  finally { loading.value = false; schedule(); }
}

/** 有同步中的灰度时每 3 秒刷新，否则每 15 秒 */
function schedule() {
  window.clearTimeout(timer);
  timer = window.setTimeout(() => load(true), releases.value.some(release => release.status === "SYNCING") ? 3000 : 15000);
}

async function command(release: GrayRelease, action: "sync" | "switch" | "switch-back" | "end") {
  busyId.value = release.id;
  try { await api.grayCommand(release.id, action); await load(true); }
  catch (error) { ElMessage.error(grayErrorMessage(error, "操作失败")); }
  finally { busyId.value = null; }
}

onMounted(() => load());
onBeforeUnmount(() => window.clearTimeout(timer));
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
    <div class="grid"><GrayReleaseCard v-for="release in active" :key="release.id" :release="release" :busy="busyId === release.id" @command="command(release, $event)" /></div>
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
