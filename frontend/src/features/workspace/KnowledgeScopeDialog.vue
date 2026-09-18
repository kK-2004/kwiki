<template>
  <div class="scope-overlay" :class="{ 'scope-overlay-compact': props.compact }" @click.self="emit('close')">
    <section class="scope-dialog" role="dialog" aria-modal="true" aria-label="选择检索范围">
      <header class="scope-header">
        <div>
          <h2>选择检索范围</h2>
          <p>支持知识库、文件夹、Wiki 多级选择；未选择时检索全部可访问内容。</p>
        </div>
        <button class="ui-icon" aria-label="关闭" @click="emit('close')"><i class="i-lucide-x" /></button>
      </header>

      <!-- 窄屏下三栏折叠为顶部 Tab 切换的单栏，宽屏隐藏并由三栏布局接管。 -->
      <nav class="mobile-tabs" aria-label="检索范围步骤">
        <button type="button" :class="{ active: pane === 'kb' }" @click="pane = 'kb'"><span>知识库</span><span class="tab-count">{{ selectedGroups.length }}</span></button>
        <button type="button" :class="{ active: pane === 'docs' }" @click="pane = 'docs'"><span>内容</span><span class="tab-count">{{ activeSelectedCount }}</span></button>
        <button type="button" :class="{ active: pane === 'selected' }" @click="pane = 'selected'"><span>已选择</span><span class="tab-count">{{ totalSelectedCount }}</span></button>
      </nav>

      <main class="scope-columns">
        <section class="col" :class="{ 'mobile-active': pane === 'kb' }">
          <div class="section-head">
            <div class="section-title">知识库</div>
            <div class="section-hint">勾选可整库检索，点击浏览内容</div>
          </div>
          <div class="search">
            <i class="i-lucide-search" />
            <input v-model="kbQuery" placeholder="搜索知识库" aria-label="搜索知识库" />
          </div>
          <div class="kb-list">
            <div v-for="kb in filteredKbs" :key="kb.id" class="kb-item" :class="{ active: activeKbId === kb.id }" :data-state="kbState(kb.id)">
              <button type="button" class="state-box" role="checkbox" :aria-checked="ariaState(kbState(kb.id))" :aria-label="`整库选择 ${kb.name}`" @click="toggleKb(kb.id)">
                <i class="i-lucide-check check-ico" /><i class="i-lucide-minus minus-ico" />
              </button>
              <button type="button" class="kb-hit" @click="chooseKb(kb.id)">
                <span class="kb-main">
                  <span class="kb-name">{{ kb.name }}</span>
                  <span class="kb-meta">{{ kbMeta(kb.id) }}</span>
                </span>
                <i class="i-lucide-chevron-right chev" />
              </button>
            </div>
            <p v-if="!filteredKbs.length" class="empty">没有匹配的知识库</p>
          </div>
        </section>

        <section class="col" :class="{ 'mobile-active': pane === 'docs' }">
          <div class="section-head">
            <div class="section-title">Wiki / 文档</div>
            <div class="section-hint">{{ activeKbHint }}</div>
          </div>
          <p v-if="loading" class="empty">正在加载…</p>
          <div v-else-if="!activeKb" class="middle-empty">
            <i class="i-lucide-folders" />
            <strong>请选择一个知识库</strong>
            <span>支持对文件夹进行全选；部分取消后会显示半选状态</span>
          </div>
          <p v-else-if="!flatNodes.length" class="empty">该知识库暂无内容</p>
          <template v-else>
            <div class="scope-card">
              <div class="scope-row">
                <div class="scope-name">{{ activeKb.name }}</div>
                <span class="scope-pill">知识库 / 文件夹均支持全选</span>
              </div>
            </div>
            <div class="tree">
              <template v-for="node in flatNodes" :key="node.id">
                <div
                  v-if="node.nodeType === 'FOLDER'"
                  class="tree-row folder-row"
                  :data-state="folderState(node)"
                  :style="{ paddingLeft: `${10 + node.depth * 14}px` }"
                  @click="toggleFolder(node)"
                >
                  <button type="button" class="state-box" role="checkbox" :aria-checked="ariaState(folderState(node))" :aria-label="`全选文件夹 ${node.title}`">
                    <i class="i-lucide-check check-ico" /><i class="i-lucide-minus minus-ico" />
                  </button>
                  <i class="node-icon i-lucide-folder" />
                  <span class="name">{{ node.title }}</span>
                  <span class="folder-badge">已选 {{ folderSelectedCount(node) }} / {{ folderTotalCount(node) }}</span>
                </div>
                <button
                  v-else
                  type="button"
                  class="tree-row doc-row"
                  :class="{ selected: isPageSelected(node.id) }"
                  :style="{ paddingLeft: `${14 + node.depth * 24}px` }"
                  @click="togglePage(node.id, activeKbId)"
                >
                  <span class="state-box"><i class="i-lucide-check check-ico" /></span>
                  <i class="node-icon i-lucide-file-text" />
                  <span class="name">{{ node.title }}</span>
                </button>
              </template>
            </div>
          </template>
        </section>

        <aside class="col" :class="{ 'mobile-active': pane === 'selected' }">
          <div class="section-head">
            <div class="section-title">当前选择</div>
            <button class="ui-icon clear-mini" title="清空" aria-label="清空选择" :disabled="!totalSelectedCount" @click="clearAll"><i class="i-lucide-eraser" /></button>
          </div>
          <div class="selection-summary">
            <div class="summary-left">
              <div class="summary-title">检索范围</div>
              <div class="summary-sub">{{ summaryText }}</div>
            </div>
            <div class="count">{{ totalSelectedCount }}</div>
          </div>
          <div v-if="!totalSelectedCount" class="empty-box">
            <i class="i-lucide-mouse-pointer-2" />
            <strong>请选择需要检索的内容</strong>
            <span>未选择时将检索全部可访问内容</span>
          </div>
          <div v-else class="selection-area">
            <div v-for="group in selectedGroups" :key="group.id" class="group-box">
              <div class="group-head"><strong>{{ group.name }}</strong><span>{{ group.selectedCount }} / {{ group.totalPages }}</span></div>
              <div class="group-body">
                <div v-if="group.whole" class="selected-row folder-selected">
                  <span class="label"><strong>整个知识库</strong><span>含全部 {{ group.totalPages }} 个内容</span></span>
                  <button type="button" class="remove" title="移除" aria-label="取消整库选择" @click="toggleKb(group.id)"><i class="i-lucide-x" /></button>
                </div>
                <template v-for="row in group.rows" :key="row.id">
                  <div v-if="row.kind === 'folder'" class="selected-row folder-selected">
                    <span class="doc-ico"><i class="i-lucide-folder" /></span>
                    <span class="label"><strong>{{ row.title }}</strong><span>{{ row.count }} 个已选内容</span></span>
                  </div>
                  <div v-else class="selected-row" :class="{ 'doc-indent': row.indented }">
                    <span class="doc-ico"><i class="i-lucide-file-text" /></span>
                    <span class="label"><strong>{{ row.title }}</strong></span>
                    <button type="button" class="remove" title="移除" :aria-label="`移除 ${row.title}`" @click="togglePage(row.id, group.id)"><i class="i-lucide-x" /></button>
                  </div>
                </template>
              </div>
            </div>
          </div>
        </aside>
      </main>

      <footer class="scope-footer">
        <div class="footer-left">
          <button type="button" class="clear" :disabled="!totalSelectedCount" @click="clearAll"><i class="i-lucide-rotate-ccw" />清空选择</button>
          <span class="footer-hint">清空会同时取消知识库、文件夹、Wiki 的选择</span>
        </div>
        <div class="actions">
          <button type="button" class="btn" @click="emit('close')">取消</button>
          <button type="button" class="btn primary" @click="apply">应用</button>
        </div>
      </footer>
    </section>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { api, type TreeNodeDto } from '../wiki/api';
import { useConversationStore } from './conversationStore';
const emit = defineEmits<{ close: [] }>();
const props = defineProps<{ compact?: boolean }>();
const store = useConversationStore();
const NARROW_QUERY = '(max-width: 980px)';
const knowledgeBases = ref<Array<{ id: number; name: string }>>([]);
const trees = ref<Record<number, TreeNodeDto[]>>({});
const activeKbId = ref<number | null>(null);
const loading = ref(false);
const kbQuery = ref('');
/** 窄屏下三栏改为顶部 Tab 切换的单栏，记录当前展示的栏。 */
const pane = ref<'kb' | 'docs' | 'selected'>('kb');
const selectedKbIds = ref(new Set(store.selectedKnowledgeBaseIds));
const selectedPageIds = ref(new Set(store.selectedPageIds));
type FlatNode = TreeNodeDto & { depth: number };
type SelectedRow = { kind: 'folder'; id: number; title: string; count: number } | { kind: 'page'; id: number; title: string; indented: boolean };

function flatten(nodes: TreeNodeDto[], depth = 0): FlatNode[] { return nodes.flatMap(n => [{ ...n, depth }, ...flatten(n.children || [], depth + 1)]); }
function pagesOf(kbId: number) { return flatten(trees.value[kbId] || []).filter(n => n.nodeType === 'PAGE'); }
function pagesUnder(node: TreeNodeDto) { return flatten(node.children || []).filter(n => n.nodeType === 'PAGE'); }

const filteredKbs = computed(() => { const q = kbQuery.value.trim().toLowerCase(); return q ? knowledgeBases.value.filter(kb => kb.name.toLowerCase().includes(q)) : knowledgeBases.value; });
const flatNodes = computed(() => activeKbId.value === null ? [] : flatten(trees.value[activeKbId.value] || []));
const activeKb = computed(() => knowledgeBases.value.find(kb => kb.id === activeKbId.value));
const activeSelectedCount = computed(() => {
  if (activeKbId.value === null) return 0;
  const pages = pagesOf(activeKbId.value);
  return selectedKbIds.value.has(activeKbId.value) ? pages.length : pages.filter(p => selectedPageIds.value.has(p.id)).length;
});
const totalSelectedCount = computed(() => selectedKbIds.value.size + selectedPageIds.value.size);
const activeKbHint = computed(() => activeKbId.value === null ? '' : `已选 ${activeSelectedCount.value} / ${pagesOf(activeKbId.value).length}`);
const summaryText = computed(() => totalSelectedCount.value ? `已选择 ${selectedGroups.value.length} 个知识库，${selectedPageIds.value.size} 个内容项` : '尚未选择任何知识库或内容');

/** 有任何勾选（整库或单页）的知识库，各生成一个分组卡片。 */
const selectedGroups = computed(() => knowledgeBases.value.map(kb => {
  const totalPages = pagesOf(kb.id).length;
  const whole = selectedKbIds.value.has(kb.id);
  const rows = whole ? [] : selectedRowsFor(kb.id);
  return { id: kb.id, name: kb.name, whole, rows, totalPages, selectedCount: whole ? totalPages : rows.filter(r => r.kind === 'page').length };
}).filter(group => group.whole || group.rows.length));

/** 汇总某个知识库内被选中的文件夹与页面，用于“当前选择”栏。 */
function selectedRowsFor(kbId: number): SelectedRow[] {
  const rows: SelectedRow[] = [];
  for (const node of trees.value[kbId] || []) {
    if (node.nodeType === 'PAGE') {
      if (selectedPageIds.value.has(node.id)) rows.push({ kind: 'page', id: node.id, title: node.title, indented: false });
      continue;
    }
    const picked = pagesUnder(node).filter(p => selectedPageIds.value.has(p.id));
    if (picked.length) {
      rows.push({ kind: 'folder', id: node.id, title: node.title, count: picked.length });
      for (const page of picked) rows.push({ kind: 'page', id: page.id, title: page.title, indented: true });
    }
  }
  return rows;
}

function kbState(kbId: number): 'checked' | 'indeterminate' | 'unchecked' {
  if (selectedKbIds.value.has(kbId)) return 'checked';
  return pagesOf(kbId).some(p => selectedPageIds.value.has(p.id)) ? 'indeterminate' : 'unchecked';
}
function folderState(node: TreeNodeDto): 'checked' | 'indeterminate' | 'unchecked' {
  const pages = pagesUnder(node);
  if (activeKbId.value !== null && selectedKbIds.value.has(activeKbId.value)) return 'checked';
  const picked = pages.filter(p => selectedPageIds.value.has(p.id));
  if (!picked.length) return 'unchecked';
  return picked.length === pages.length ? 'checked' : 'indeterminate';
}
function ariaState(state: string) { return state === 'checked' ? 'true' : state === 'indeterminate' ? 'mixed' : 'false'; }
function kbMeta(kbId: number) {
  const all = pagesOf(kbId);
  const picked = selectedKbIds.value.has(kbId) ? all.length : all.filter(p => selectedPageIds.value.has(p.id)).length;
  return picked ? `已选 ${picked} / ${all.length}` : `${all.length} 个内容项`;
}
function isPageSelected(id: number) { return activeKbId.value !== null && (selectedKbIds.value.has(activeKbId.value) || selectedPageIds.value.has(id)); }
function folderSelectedCount(node: TreeNodeDto) {
  return activeKbId.value !== null && selectedKbIds.value.has(activeKbId.value)
    ? pagesUnder(node).length
    : pagesUnder(node).filter(p => selectedPageIds.value.has(p.id)).length;
}
function folderTotalCount(node: TreeNodeDto) { return pagesUnder(node).length; }

/** 勾选知识库 = 整库纳入检索（含后续新增内容），再次点击取消。 */
function toggleKb(id: number) {
  const next = new Set(selectedKbIds.value);
  const pages = pagesOf(id);
  if (next.has(id)) next.delete(id);
  else next.add(id);
  // 整库选择和逐篇选择互斥，避免摘要数量重复，并让整库状态能稳定表达“全部”。
  const pageIds = new Set(selectedPageIds.value);
  pages.forEach(page => pageIds.delete(page.id));
  selectedKbIds.value = next;
  selectedPageIds.value = pageIds;
}
/** 点击文件夹 = 全选/取消其下全部页面（后端只接收页面 ID，因此展开为页面集合）。 */
function toggleFolder(node: TreeNodeDto) {
  const checked = folderState(node) === 'checked';
  const next = new Set(selectedPageIds.value);
  const kbId = activeKbId.value;
  if (kbId !== null && selectedKbIds.value.has(kbId)) {
    // 从整库选择中取消一个文件夹时，转换为“除该文件夹外的所有页面”。
    const folderIds = new Set(pagesUnder(node).map(page => page.id));
    next.clear();
    pagesOf(kbId).forEach(page => { if (!folderIds.has(page.id)) next.add(page.id); });
    const nextKbs = new Set(selectedKbIds.value);
    nextKbs.delete(kbId);
    selectedKbIds.value = nextKbs;
  } else {
    pagesUnder(node).forEach(p => checked ? next.delete(p.id) : next.add(p.id));
  }
  selectedPageIds.value = next;
}
function togglePage(id: number, sourceKbId: number | null = activeKbId.value) {
  const next = new Set(selectedPageIds.value);
  const kbId = sourceKbId;
  if (kbId !== null && selectedKbIds.value.has(kbId)) {
    // 点击整库中的单篇内容即表示取消该篇，保留同库其余页面的选择。
    next.clear();
    pagesOf(kbId).forEach(page => { if (page.id !== id) next.add(page.id); });
    const nextKbs = new Set(selectedKbIds.value);
    nextKbs.delete(kbId);
    selectedKbIds.value = nextKbs;
  } else {
    next.has(id) ? next.delete(id) : next.add(id);
  }
  selectedPageIds.value = next;
}
function chooseKb(id: number) { activeKbId.value = id; if (props.compact || window.matchMedia(NARROW_QUERY).matches) pane.value = 'docs'; }
function clearAll() { selectedKbIds.value = new Set(); selectedPageIds.value = new Set(); activeKbId.value = null; }
function apply() { store.selectedKnowledgeBaseIds = [...selectedKbIds.value]; store.selectedPageIds = [...selectedPageIds.value]; emit('close'); }

onMounted(async () => {
  knowledgeBases.value = await api.json('/knowledge-bases');
  loading.value = true;
  try {
    // 目录树用于展示已选数量、文件夹半选与“当前选择”汇总，一次性并行加载；单个失败不影响其余。
    const entries = await Promise.all(knowledgeBases.value.map(async kb => {
      try { return [kb.id, await api.json<TreeNodeDto[]>(`/knowledge-bases/${kb.id}/tree`)] as [number, TreeNodeDto[]]; }
      catch { return [kb.id, []] as [number, TreeNodeDto[]]; }
    }));
    const map: Record<number, TreeNodeDto[]> = {};
    for (const [id, tree] of entries) map[id] = tree;
    trees.value = map;
    if (knowledgeBases.value[0]) activeKbId.value = knowledgeBases.value[0].id;
  } finally { loading.value = false; }
});
</script>
<style scoped>
/* 视觉规范与 prototype「选择检索范围 v6」设计稿对齐：三栏 31/41/28、绿色 #447d5b 主色。 */
.scope-overlay{position:absolute;inset:0;z-index:30;display:grid;place-items:center;min-height:0;padding:20px;background:#1b2b2247;overflow:hidden;box-sizing:border-box}
.scope-dialog{width:min(1240px,100%);height:min(570px,100%);max-height:min(570px,100%);display:grid;grid-template-rows:auto auto minmax(0,1fr) max-content;overflow:hidden;box-sizing:border-box;border:1px solid #19372414;border-radius:24px;background:#fff;box-shadow:0 18px 60px #1b2b221a}
/* 桌面端隐藏标签栏后仍保留行归属，避免按钮栏落入可伸缩的内容行。 */
.scope-header{grid-row:1}
.mobile-tabs{grid-row:2}
.scope-columns{grid-row:3}
.scope-footer{grid-row:4}
.scope-header{padding:22px 28px 18px;border-bottom:1px solid #e6ece8;display:flex;align-items:flex-start;justify-content:space-between;gap:20px}
.scope-header h2{margin:0;font-size:26px;font-weight:700;letter-spacing:-.02em;line-height:1.2;color:#18221c}
.scope-header p{margin:7px 0 0;color:#8a9990;font-size:13px}
.mobile-tabs{display:none}
.scope-columns{display:grid;grid-template-columns:31% 41% 28%;height:100%;min-height:0;overflow:hidden}
.col{height:100%;min-height:0;padding:22px 24px;overflow:auto;min-width:0;box-sizing:border-box;scrollbar-width:thin}
.col+.col{border-left:1px solid #e6ece8}
.section-head{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:16px}
.section-title{font-size:12px;font-weight:700;letter-spacing:.04em;text-transform:uppercase;color:#75837b}
.section-hint{font-size:12px;color:#9aa69f;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.search{height:40px;display:flex;align-items:center;gap:10px;padding:0 12px;border:1px solid #e6ece8;background:#fafcfb;border-radius:11px;margin-bottom:14px}
.search i{font-size:16px;color:#9aaa9f}
.search input{flex:1;min-width:0;border:0;outline:none;background:transparent;font:inherit;color:#18221c}
.kb-list{display:flex;flex-direction:column;gap:8px}
.kb-item{min-height:64px;width:100%;padding:10px 12px;border-radius:14px;border:1px solid transparent;background:transparent;display:flex;align-items:center;gap:12px;text-align:left;color:#4f5f56;transition:background-color .18s,border-color .18s,box-shadow .18s}
.kb-item:hover{background:#f7f9f7;border-color:#edf2ee}
.kb-item.active{background:linear-gradient(180deg,#f8fbf9 0%,#f2f7f4 100%);border-color:#d6e3db;box-shadow:0 1px 0 #ffffffd9 inset}
.state-box{width:20px;height:20px;padding:0;border-radius:6px;border:1.5px solid #bac6bf;background:#fff;display:grid;place-items:center;color:#fff;flex:0 0 auto;cursor:pointer;transition:background-color .15s,border-color .15s}
.state-box i{display:none;font-size:13px}
[data-state="checked"]>.state-box,[data-state="indeterminate"]>.state-box,.tree-row.selected>.state-box{background:#447d5b;border-color:#447d5b}
[data-state="checked"]>.state-box .check-ico,.tree-row.selected>.state-box .check-ico{display:block}
[data-state="indeterminate"]>.state-box .minus-ico{display:block}
.kb-hit{flex:1;min-width:0;display:flex;align-items:center;gap:12px;border:0;background:none;padding:0;font:inherit;color:inherit;text-align:left;cursor:pointer}
.kb-main{flex:1;min-width:0;display:flex;flex-direction:column;gap:4px}
.kb-name{font-size:15px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;color:#24352d}
.kb-meta{font-size:12px;color:#93a099}
.kb-item.active .kb-meta{color:#7f8f86}
.chev{font-size:18px;color:#98a59e;flex:0 0 auto;margin-left:auto}
.scope-card{border:1px solid #e6ece8;border-radius:14px;background:#fbfcfb;padding:12px 14px;margin-bottom:14px}
.scope-row{display:flex;align-items:center;justify-content:space-between;gap:12px}
.scope-name{display:flex;align-items:center;gap:10px;min-width:0;font-size:14px;font-weight:600;color:#31453a;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.scope-pill{font-size:12px;color:#5f7468;border:1px solid #e0e9e3;background:#eef4ef;border-radius:999px;padding:5px 8px;flex:0 0 auto;white-space:nowrap}
.tree{display:flex;flex-direction:column;gap:6px}
.tree-row{min-height:48px;display:flex;align-items:center;gap:10px;padding:8px 10px;border-radius:11px;color:#3c4d44;cursor:pointer;user-select:none;transition:background-color .16s}
.tree-row .name{flex:1;min-width:0;font-size:14px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.tree-row:hover{background:#f7f9f7}
.tree-row.selected{background:#f1f6f2}
.tree-row.folder-row{background:#fafcfb;border:1px solid #eef2ef}
.tree-row.folder-row:hover{background:#f5f8f6}
.tree-row.doc-row{width:100%;border:0;font:inherit;text-align:left;background:transparent}
.node-icon{font-size:18px;color:#728279;flex:0 0 auto}
.folder-badge{font-size:11px;padding:4px 7px;border-radius:999px;color:#61756a;background:#eef3f0;border:1px solid #e2eae5;flex:0 0 auto;white-space:nowrap}
.selection-summary{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:14px 15px;border-radius:14px;background:#f7faf8;border:1px solid #e4ebe6;margin-bottom:12px}
.summary-left{min-width:0}
.summary-title{font-size:14px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;color:#18221c}
.summary-sub{margin-top:3px;font-size:12px;color:#89978f}
.count{min-width:34px;height:28px;padding:0 9px;border-radius:999px;background:#e7f1ea;color:#366a4a;display:grid;place-items:center;font-size:12px;font-weight:700;flex:0 0 auto}
.selection-area{display:flex;flex-direction:column;gap:10px;min-height:0}
.group-box{border:1px solid #e8ede9;border-radius:14px;overflow:hidden;background:#fff}
.group-head{min-height:42px;padding:10px 12px;display:flex;align-items:center;justify-content:space-between;gap:10px;background:#fafcfb;border-bottom:1px solid #eef2ef}
.group-head strong{font-size:13px;color:#31453a;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.group-head span{font-size:12px;color:#91a097;flex:0 0 auto}
.group-body{padding:6px;display:flex;flex-direction:column;gap:2px}
.selected-row{display:flex;align-items:center;gap:10px;padding:9px 8px;border-radius:10px;border:1px solid transparent}
.selected-row:hover{background:#fafbfa;border-color:#edf1ee}
.selected-row.folder-selected{background:#fbfcfb;border-color:#eef2ef;margin-bottom:4px}
.selected-row.doc-indent{padding-left:36px}
.doc-ico{width:30px;height:30px;border-radius:8px;background:#edf3ef;color:#587060;display:grid;place-items:center;flex:0 0 auto}
.doc-ico i{font-size:15px}
.label{flex:1;min-width:0}
.label strong{display:block;font-size:13px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;color:#3a4b42}
.label span{display:block;margin-top:2px;font-size:11px;color:#97a49c}
.remove{width:28px;height:28px;border:0;background:transparent;border-radius:8px;color:#99a79f;display:grid;place-items:center;cursor:pointer;flex:0 0 auto}
.remove:hover{background:#f0f3f1;color:#61746a}
.remove i{font-size:14px}
.empty{margin:4px 0 0;color:#9aa49d;font-size:13px}
.empty-box,.middle-empty{border:1px dashed #dfe7e1;border-radius:14px;background:#fbfcfb;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;color:#8d9a92;padding:24px}
.empty-box{min-height:220px}
.middle-empty{min-height:320px}
.empty-box i,.middle-empty i{font-size:28px;margin-bottom:10px;color:#9eada3}
.empty-box strong,.middle-empty strong{font-size:14px;color:#6a7b71}
.empty-box span,.middle-empty span{font-size:12px;margin-top:5px}
.scope-footer{min-height:0;border-top:1px solid #e6ece8;display:flex;justify-content:space-between;align-items:center;gap:18px;padding:12px 24px}
.footer-left{display:flex;align-items:center;gap:10px;color:#7d8c83;font-size:13px;min-width:0}
.clear{height:40px;padding:0 15px;border-radius:11px;border:1px solid #d6e0da;background:#fff;color:#4d5f54;display:inline-flex;align-items:center;gap:8px;cursor:pointer;flex:0 0 auto}
.clear:hover{background:#f8faf8;border-color:#ced9d2}
.clear i{font-size:15px}
.actions{display:flex;gap:10px;flex:0 0 auto}
.btn{height:42px;padding:0 20px;border-radius:11px;border:1px solid #d6e0da;background:#fff;color:#405247;font-weight:600;cursor:pointer}
.btn:hover{background:#f7f9f7}
.btn.primary{border-color:#447d5b;background:#447d5b;color:#fff;box-shadow:0 5px 14px #447d5b2e}
.btn.primary:hover{background:#2f6d4a}
.clear-mini{width:30px;height:30px}
.clear-mini i{font-size:15px}

/* 浮窗的宽度由容器决定，不能依赖桌面视口的 media query。 */
.scope-overlay-compact{padding:8px}
.scope-overlay-compact .scope-dialog{width:100%;height:100%;min-height:0;border-radius:14px}
.scope-overlay-compact .scope-header{padding:14px 16px 11px}
.scope-overlay-compact .scope-header h2{font-size:18px}
.scope-overlay-compact .scope-header p{font-size:11px;line-height:1.45}
.scope-overlay-compact .mobile-tabs{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:3px;padding:6px 8px;border-bottom:1px solid #e6ece8}
.scope-overlay-compact .mobile-tabs button{min-width:0;height:36px;padding:0 5px;border:0;border-radius:8px;background:transparent;color:#7f8d85;display:flex;align-items:center;justify-content:center;gap:4px;font-size:11px;font-weight:600;white-space:nowrap;cursor:pointer}
.scope-overlay-compact .mobile-tabs button.active{background:#eef5f0;color:#315f43}
.scope-overlay-compact .tab-count{min-width:17px;height:17px;padding:0 4px;border-radius:999px;background:#eef1ef;color:#7c8b82;display:grid;place-items:center;font-size:10px;line-height:1}
.scope-overlay-compact .mobile-tabs button.active .tab-count{background:#dfece3;color:#356749}
.scope-overlay-compact .scope-columns{display:block;height:100%;min-height:0;overflow:hidden}
.scope-overlay-compact .col{display:none;width:100%;height:100%;min-height:0;padding:13px 14px;border-left:0!important;overscroll-behavior:contain}
.scope-overlay-compact .col.mobile-active{display:block}
.scope-overlay-compact .section-head{margin-bottom:11px}
.scope-overlay-compact .section-title{font-size:11px}
.scope-overlay-compact .section-hint{font-size:10px}
.scope-overlay-compact .search{height:36px;margin-bottom:10px}
.scope-overlay-compact .kb-item{min-height:56px;padding:8px 9px;gap:8px}
.scope-overlay-compact .kb-hit{gap:8px}
.scope-overlay-compact .kb-name{font-size:13px}
.scope-overlay-compact .kb-meta{font-size:10px}
.scope-overlay-compact .scope-pill{display:none}
.scope-overlay-compact .tree-row{min-height:42px;padding-top:6px;padding-bottom:6px}
.scope-overlay-compact .tree-row .name{font-size:12px}
.scope-overlay-compact .folder-badge{font-size:10px;padding:3px 5px}
.scope-overlay-compact .scope-footer{min-height:0;padding:9px 10px;gap:6px}
.scope-overlay-compact .footer-hint{display:none}
.scope-overlay-compact .clear{height:36px;padding:0 10px;font-size:11px}
.scope-overlay-compact .btn{height:36px;padding:0 13px;font-size:12px}

/* 窄屏 / 平板：三栏折叠为顶部 Tab + 单栏。 */
@media (max-width:980px){
  .scope-overlay{padding:12px}
  .scope-dialog{width:100%;height:100%;min-height:0;border-radius:20px}
  .scope-header{padding:18px 20px 14px}
  .scope-header h2{font-size:22px}
  .scope-header p{font-size:12px;line-height:1.55}
  .mobile-tabs{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:4px;padding:8px 12px;border-bottom:1px solid #e6ece8}
  .mobile-tabs button{min-width:0;height:42px;padding:0 8px;border:0;border-radius:10px;background:transparent;color:#7f8d85;display:flex;align-items:center;justify-content:center;gap:6px;font-size:13px;font-weight:600;white-space:nowrap;cursor:pointer}
  .mobile-tabs button:hover{background:#f6f8f6}
  .mobile-tabs button.active{background:#eef5f0;color:#315f43}
  .tab-count{min-width:20px;height:20px;padding:0 5px;border-radius:999px;background:#eef1ef;color:#7c8b82;display:grid;place-items:center;font-size:11px;line-height:1}
  .mobile-tabs button.active .tab-count{background:#dfece3;color:#356749}
  .scope-columns{display:block;min-height:0}
  .col{display:none;width:100%;height:100%;min-height:0;padding:16px 18px;border-left:0!important;overscroll-behavior:contain}
  .col.mobile-active{display:block}
  .footer-hint{display:none}
  .scope-footer{min-height:64px;padding:12px 14px;gap:8px}
  .clear{height:40px;padding:0 12px}
  .btn{height:40px;padding:0 16px}
}
/* 手机：弹窗全屏铺满。 */
@media (max-width:560px){
  .scope-overlay{padding:0}
  .scope-dialog{border:0;border-radius:0}
  .scope-header{padding:15px 16px 12px}
  .scope-header h2{font-size:21px}
  .mobile-tabs{padding:7px 8px}
  .col{padding:14px 12px 18px}
  .scope-pill{display:none}
  .selection-summary{padding:12px}
  .scope-footer{padding:10px 12px}
  .clear{width:40px;padding:0;justify-content:center;font-size:0;gap:0}
  .clear i{font-size:16px}
  .btn{min-width:72px;padding:0 13px}
}
@media (max-width:380px){
  .tab-count{display:none}
  .actions{gap:6px}
  .btn{min-width:66px;padding:0 10px}
}
</style>
