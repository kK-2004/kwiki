<template>
  <div class="app" data-testid="workspace">
    <aside
      class="global"
      :class="{ 'drawer-left': narrow, open: globalDrawerOpen }"
      aria-label="全局导航"
      data-testid="global-nav"
    >
      <div class="brand-row">
        <a class="brand" href="#" aria-label="kwiki 首页">
          <img class="logo" src="/kwiki-logo.svg" alt="" width="28" height="28" /><span>kwiki</span>
        </a>
        <div class="icons">
          <button type="button" class="icon" aria-label="全局搜索">
            <i class="i-lucide-search" aria-hidden="true"></i>
          </button>
          <button type="button" class="icon" aria-label="收起侧边栏" @click="closeDrawers">
            <i class="i-lucide-panel-left-close" aria-hidden="true"></i>
          </button>
        </div>
      </div>
      <nav class="nav" aria-label="主要功能">
        <button type="button">
          <span class="nav-mark" aria-hidden="true"><i class="i-lucide-message-square-plus"></i></span>新对话
        </button>
        <button type="button" class="active">
          <span class="nav-mark" aria-hidden="true"><i class="i-lucide-library"></i></span>知识库
        </button>
        <button type="button">
          <span class="nav-mark" aria-hidden="true"><i class="i-lucide-bot"></i></span>智能体
        </button>
        <button type="button">
          <span class="nav-mark" aria-hidden="true"><i class="i-lucide-share-2"></i></span>共享空间
        </button>
      </nav>
      <div class="recent-title">今天</div>
      <a class="recent" href="#">研发知识库接入规范</a>
      <a class="recent" href="#">员工差旅与报销指南</a>
      <a class="recent" href="#">Agentic RAG 召回评估</a>
      <div class="account">
        <span class="avatar" aria-hidden="true">K</span>
        <div class="account-copy"><strong>kwiki 管理员</strong><span>admin@kwiki.local</span></div>
        <span class="account-caret" aria-hidden="true"><i class="i-lucide-chevron-down"></i></span>
      </div>
    </aside>

    <aside
      class="tree"
      :class="{ 'drawer-left': narrow, open: treeDrawerOpen }"
      id="middle-column"
      aria-label="Wiki 页面导航"
      data-testid="middle-column"
    >
      <div class="tree-title">
        <span>内部知识库</span><span class="faint">›</span><span>文档</span><span>/</span>
        <span class="wiki">Wiki</span>
      </div>
      <div class="tree-sub">已发布页面自动生成父子块，支持智能检索</div>
      <WikiSearch v-if="middleTab === 'knowledge'">
        <template #toolbar>
          <button type="button" class="index">
            <i class="i-lucide-list" aria-hidden="true"></i>索引
          </button>
          <div class="tabs-row">
            <div class="tabs" role="tablist" aria-label="Wiki 视图">
              <button
                type="button"
                role="tab"
                :aria-selected="knowledgeSelected"
                @click="middleTab = 'knowledge'"
              >
                知识 <span class="count">{{ knowledgeCount }}</span>
              </button>
              <button
                type="button"
                role="tab"
                :aria-selected="summarySelected"
                @click="middleTab = 'summary'"
              >
                摘要 <span class="count">{{ summaryCount }}</span>
              </button>
            </div>
            <div class="tree-tools">
              <button type="button" class="icon" aria-label="树形视图">
                <i class="i-lucide-list-tree" aria-hidden="true"></i>
              </button>
              <button type="button" class="icon" aria-label="新建目录">
                <i class="i-lucide-folder-plus" aria-hidden="true"></i>
              </button>
              <button type="button" class="icon" aria-label="新建页面">
                <i class="i-lucide-file-plus" aria-hidden="true"></i>
              </button>
            </div>
          </div>
        </template>
      </WikiSearch>
      <div v-else class="tree-scroll">
        <WikiSummaryPanel />
      </div>
    </aside>

    <section class="content" data-testid="content-column">
      <div class="mobile">
        <button type="button" class="icon" aria-label="打开全局导航" @click="openGlobalDrawer">
          <i class="i-lucide-menu" aria-hidden="true"></i>
        </button>
        <strong>kwiki</strong>
        <button type="button" class="icon" aria-label="打开页面目录" @click="openTreeDrawer">
          <i class="i-lucide-panel-left-open" aria-hidden="true"></i>
        </button>
      </div>
      <header class="workspace-head">
        <div class="crumbs">
          <strong>知识库</strong><span>›</span><strong>kwiki 内部知识库</strong><span>›</span>
          <span>文档</span><span>/</span><span class="active">Wiki</span><span>/</span><span>图谱</span>
        </div>
        <div class="workspace-sub">
          支持 Markdown、DOCX 等文本型文档解析，以父子块构建可检索、可追溯的企业知识
        </div>
      </header>
      <main class="document">
        <RouterView />
      </main>
    </section>

    <div v-if="overlayVisible" class="overlay" @click="closeDrawers"></div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { RouterView } from 'vue-router';
import WikiSearch from './WikiSearch.vue';
import WikiSummaryPanel from './WikiSummaryPanel.vue';
import { useWikiStore } from '../store';
import type { TreeNodeDto } from '../api';

defineProps<{ kbId?: string; pageId?: string }>();

const store = useWikiStore();
const middleTab = computed({
  get: () => store.middleTab,
  set: (value) => {
    store.middleTab = value;
  },
});
const knowledgeSelected = computed(() => middleTab.value === 'knowledge');
const summarySelected = computed(() => middleTab.value === 'summary');

function countPages(nodes: TreeNodeDto[]): number {
  return nodes.reduce(
    (total, node) =>
      total + (node.nodeType === 'PAGE' ? 1 : 0) + countPages(node.children ?? []),
    0,
  );
}
const knowledgeCount = computed(() => countPages(store.tree));
const summaryCount = computed(() => 0);

const viewportWidth = ref(1280);
const onResize = () => {
  viewportWidth.value = window.innerWidth;
};
onMounted(() => {
  onResize();
  window.addEventListener('resize', onResize);
});
onBeforeUnmount(() => window.removeEventListener('resize', onResize));

const narrow = computed(() => viewportWidth.value < 1024);
const globalDrawerOpen = ref(false);
const treeDrawerOpen = ref(false);
const overlayVisible = computed(
  () => narrow.value && (globalDrawerOpen.value || treeDrawerOpen.value),
);
function openGlobalDrawer() {
  globalDrawerOpen.value = true;
  treeDrawerOpen.value = false;
}
function openTreeDrawer() {
  treeDrawerOpen.value = true;
  globalDrawerOpen.value = false;
}
function closeDrawers() {
  globalDrawerOpen.value = false;
  treeDrawerOpen.value = false;
}
</script>

<style scoped>
.app {
  display: grid;
  grid-template-columns: var(--kwiki-nav-width) var(--kwiki-tree-width) minmax(0, 1fr);
  height: 100vh;
}
.global,
.tree {
  min-width: 0;
  border-right: 1px solid var(--kwiki-line);
  z-index: 20;
}
.global {
  display: flex;
  flex-direction: column;
  padding: 18px 10px 12px;
  background: var(--kwiki-sidebar-bg);
  overflow: auto;
}
.brand-row {
  height: 38px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 10px;
  margin-bottom: 12px;
}
.brand {
  display: flex;
  align-items: center;
  gap: 9px;
  text-decoration: none;
  font-size: 21px;
  font-weight: 780;
  letter-spacing: 0.02em;
  color: #203537;
}
.logo {
  width: 28px;
  height: 28px;
  display: block;
  flex: none;
}
.icons {
  display: flex;
  gap: 2px;
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
  font-size: 17px;
}
.icon:hover {
  background: #eef1ef;
  color: #303636;
}
.nav {
  display: grid;
  gap: 4px;
}
.nav button {
  height: 39px;
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 0 12px;
  border: 0;
  background: none;
  border-radius: 7px;
  text-align: left;
  font-weight: 620;
  cursor: pointer;
  color: var(--kwiki-ink);
}
.nav button:hover {
  background: #f0f2f1;
}
.nav .active {
  background: #edf2ef;
  color: var(--kwiki-green-dark);
}
.nav-mark {
  width: 18px;
  display: inline-flex;
  justify-content: center;
  font-size: 16px;
}
.recent-title {
  margin: 16px 12px 6px;
  color: #b0b5b5;
  font-size: 12px;
}
.recent {
  display: block;
  padding: 8px 12px;
  border-radius: 6px;
  text-decoration: none;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: #505656;
}
.recent:hover {
  background: #f0f2f1;
}
.account {
  margin-top: auto;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 8px 3px;
}
.avatar {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--kwiki-green);
  color: #fff;
  font-size: 12px;
  font-weight: 750;
}
.account-copy {
  min-width: 0;
  flex: 1;
  line-height: 1.35;
}
.account-copy strong {
  display: block;
  font-size: 13px;
}
.account-copy span {
  color: var(--kwiki-muted);
  font-size: 11px;
}
.account-caret {
  display: inline-flex;
  font-size: 14px;
  color: var(--kwiki-muted);
}
.tree {
  display: flex;
  flex-direction: column;
  padding: 18px 14px 14px;
  background: var(--kwiki-panel);
}
.tree-title {
  display: flex;
  align-items: center;
  gap: 7px;
  min-height: 34px;
  padding: 0 3px;
  font-weight: 700;
  font-size: 15px;
}
.tree-title .faint {
  color: #a5aaaa;
}
.tree-title .wiki {
  color: var(--kwiki-green);
}
.tree-sub {
  margin: 3px 3px 16px;
  color: #a0a6a6;
  font-size: 12px;
}
.index {
  height: 36px;
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 0 10px;
  margin: 0 3px 7px;
  border: 0;
  background: none;
  border-bottom: 1px solid var(--kwiki-line);
  color: #505656;
  cursor: pointer;
}
.tabs-row {
  display: flex;
  align-items: center;
  gap: 16px;
  height: 40px;
  padding: 0 5px;
  border-bottom: 1px solid var(--kwiki-line);
}
.tabs {
  display: flex;
  align-items: center;
  gap: 16px;
}
.tabs button {
  position: relative;
  height: 40px;
  padding: 0 2px;
  border: 0;
  background: none;
  color: #818888;
  cursor: pointer;
}
.tabs button.active,
.tabs button[aria-selected='true'] {
  color: var(--kwiki-green-dark);
  font-weight: 650;
}
.tabs button[aria-selected='true']::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: -1px;
  height: 2px;
  background: var(--kwiki-green);
}
.count {
  margin-left: 3px;
  color: #a2a7a7;
  font-size: 11px;
}
.tree-tools {
  margin-left: auto;
  display: flex;
}
.tree-tools .icon {
  width: 27px;
  height: 27px;
  font-size: 15px;
}
.tree-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 10px 0 26px;
  scrollbar-width: thin;
}
.content {
  min-width: 0;
  height: 100vh;
  overflow: auto;
  position: relative;
}
.mobile {
  display: none;
}
.workspace-head {
  padding: 27px 34px 0;
}
.crumbs {
  display: flex;
  align-items: center;
  gap: 9px;
  flex-wrap: wrap;
  color: #a0a5a5;
}
.crumbs strong {
  color: #5a6060;
}
.crumbs .active {
  color: var(--kwiki-green);
  font-weight: 650;
}
.workspace-sub {
  margin-top: 7px;
  color: #a5aaaa;
  font-size: 12px;
}
.document {
  max-width: 1050px;
  padding: 24px 38px 80px;
}
.overlay {
  display: none;
}
@media (max-width: 1230px) {
  .document {
    padding-left: 30px;
    padding-right: 30px;
  }
}
@media (max-width: 1023px) {
  .app {
    display: block;
    height: auto;
  }
  .global,
  .tree {
    position: fixed;
    top: 0;
    bottom: 0;
    left: 0;
    width: min(86vw, 330px);
    transform: translateX(-105%);
    transition: transform 0.2s ease;
    box-shadow: 14px 0 42px rgba(25, 42, 33, 0.12);
  }
  .global.drawer-left.open,
  .tree.drawer-left.open {
    transform: translateX(0);
  }
  .content {
    height: auto;
    min-height: 100vh;
    overflow: visible;
  }
  .mobile {
    position: sticky;
    top: 0;
    z-index: 11;
    height: 52px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 0 13px;
    border-bottom: 1px solid var(--kwiki-line);
    background: rgba(255, 255, 255, 0.95);
    backdrop-filter: blur(12px);
  }
  .mobile .icon {
    font-size: 18px;
  }
  .workspace-head {
    padding: 22px 20px 0;
  }
  .document {
    padding: 20px 20px 88px;
  }
  .overlay {
    display: block;
    position: fixed;
    inset: 0;
    z-index: 40;
    background: rgba(22, 31, 27, 0.25);
    backdrop-filter: blur(1px);
  }
}
@media (max-width: 640px) {
  .workspace-sub {
    display: none;
  }
}
</style>
