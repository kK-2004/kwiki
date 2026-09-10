<template>
  <div class="scope-overlay" @click.self="emit('close')">
    <section class="scope-dialog" role="dialog" aria-modal="true" aria-label="选择可访问的知识库">
      <header><div><h2>选择检索范围</h2><p>可多选知识库和 Wiki，回答只会检索所选内容。</p></div><button class="ui-icon" aria-label="关闭" @click="emit('close')"><i class="i-lucide-x" /></button></header>
      <div class="scope-columns">
        <section><h3>知识库</h3><button v-for="kb in knowledgeBases" :key="kb.id" class="kb-row" :class="{ active: activeKbId === kb.id }" @click="chooseKb(kb.id)"><input type="checkbox" :checked="selectedKbIds.has(kb.id)" @click.stop @change="toggleKb(kb.id)" /> <span>{{ kb.name }}</span><i class="i-lucide-chevron-right" /></button></section>
        <section><h3>Wiki</h3><p v-if="loading" class="empty">正在加载…</p><p v-else-if="!flatNodes.length" class="empty">该知识库暂无 Wiki</p><label v-for="node in flatNodes" :key="node.id" class="wiki-row" :style="{ paddingLeft: `${12 + node.depth * 16}px` }"><input v-if="node.nodeType === 'PAGE'" type="checkbox" :checked="selectedPageIds.has(node.id)" @change="togglePage(node.id)" /><i v-else class="i-lucide-folder" /><span>{{ node.title }}</span></label></section>
        <section><h3>当前选择</h3><p v-if="!selectedKbIds.size && !selectedPageIds.size" class="empty">未指定时检索全部可访问内容</p><details v-for="kb in selectedGroups" :key="kb.id"><summary><span>{{ kb.name }}</span><small>已选 {{ kb.pages.length }}/{{ pageCount(kb.id) }}</small><i class="i-lucide-chevron-down" /></summary><div><span v-if="selectedKbIds.has(kb.id)" class="all-chip">整个知识库</span><button v-for="page in kb.pages" :key="page.id" @click="togglePage(page.id)">{{ page.title }}<i class="i-lucide-x" /></button></div></details></section>
      </div>
      <footer><button class="ui-button" @click="clearAll">清空（全部可访问）</button><span /><button class="ui-button" @click="emit('close')">取消</button><button class="ui-button primary" @click="apply">应用</button></footer>
    </section>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { api, type TreeNodeDto } from '../wiki/api';
import { useConversationStore } from './conversationStore';
const emit = defineEmits<{ close: [] }>();
const store = useConversationStore();
const knowledgeBases = ref<Array<{ id:number; name:string }>>([]);
const trees = ref<Record<number, TreeNodeDto[]>>({});
const activeKbId = ref<number | null>(null); const loading = ref(false);
const selectedKbIds = ref(new Set(store.selectedKnowledgeBaseIds));
const selectedPageIds = ref(new Set(store.selectedPageIds));
type FlatNode = TreeNodeDto & { depth:number };
function flatten(nodes: TreeNodeDto[], depth = 0): FlatNode[] { return nodes.flatMap(n => [{ ...n, depth }, ...flatten(n.children || [], depth + 1)]); }
const flatNodes = computed(() => activeKbId.value ? flatten(trees.value[activeKbId.value] || []) : []);
const allPages = computed(() => knowledgeBases.value.flatMap(kb => flatten(trees.value[kb.id] || []).filter(n => n.nodeType === 'PAGE').map(n => ({ ...n, kbId: kb.id }))));
const selectedGroups = computed(() => knowledgeBases.value.map(kb => ({ ...kb, pages: allPages.value.filter(p => p.kbId === kb.id && selectedPageIds.value.has(p.id)) })).filter(kb => selectedKbIds.value.has(kb.id) || kb.pages.length));
async function chooseKb(id:number) { activeKbId.value = id; if (trees.value[id]) return; loading.value = true; try { trees.value = { ...trees.value, [id]: await api.json<TreeNodeDto[]>(`/knowledge-bases/${id}/tree`) }; } finally { loading.value = false; } }
function toggleKb(id:number) { const next = new Set(selectedKbIds.value); next.has(id) ? next.delete(id) : next.add(id); selectedKbIds.value = next; }
function togglePage(id:number) { const next = new Set(selectedPageIds.value); next.has(id) ? next.delete(id) : next.add(id); selectedPageIds.value = next; }
function pageCount(id:number) { return flatten(trees.value[id] || []).filter(n => n.nodeType === 'PAGE').length; }
function clearAll() { selectedKbIds.value = new Set(); selectedPageIds.value = new Set(); }
function apply() { store.selectedKnowledgeBaseIds = [...selectedKbIds.value]; store.selectedPageIds = [...selectedPageIds.value]; emit('close'); }
onMounted(async () => { knowledgeBases.value = await api.json('/knowledge-bases'); if (knowledgeBases.value[0]) await chooseKb(knowledgeBases.value[0].id); });
</script>
<style scoped>
.scope-overlay{position:absolute;inset:0;z-index:30;display:grid;place-items:center;padding:20px;background:#19332255}.scope-dialog{width:min(980px,100%);max-height:90%;display:flex;flex-direction:column;overflow:hidden;border-radius:16px;background:white;box-shadow:0 24px 80px #17352233}.scope-dialog>header,.scope-dialog>footer{display:flex;align-items:center;gap:12px;padding:18px 22px}.scope-dialog>header{justify-content:space-between;border-bottom:1px solid #e5ebe7}.scope-dialog h2{margin:0;font-size:20px}.scope-dialog header p{margin:5px 0 0;color:#8b9890;font-size:12px}.scope-columns{display:grid;grid-template-columns:1fr 1.4fr 1.2fr;min-height:360px;overflow:hidden}.scope-columns>section{overflow:auto;padding:18px;border-right:1px solid #edf1ee}.scope-columns>section:last-child{border:0}.scope-columns h3{margin:0 0 14px;font-size:12px;color:#7e8e84}.kb-row,.wiki-row{width:100%;display:flex;align-items:center;gap:8px;padding:10px 12px;border:0;border-radius:8px;background:none;text-align:left;color:#52645a}.kb-row{cursor:pointer}.kb-row.active{background:#edf5ef;color:#34744d}.kb-row i{margin-left:auto}.wiki-row{box-sizing:border-box}.empty{color:#9aa49d;font-size:12px}details{border:1px solid #e3eae5;border-radius:9px;margin-bottom:9px}summary{display:flex;align-items:center;gap:8px;padding:11px;cursor:pointer;list-style:none}summary small{margin-left:auto;color:#8c9a90}details>div{display:flex;flex-wrap:wrap;gap:6px;padding:0 10px 10px}details button,.all-chip{display:inline-flex;align-items:center;gap:4px;border:0;border-radius:6px;padding:6px 8px;background:#edf4ef;color:#477057;font-size:11px}.scope-dialog>footer{border-top:1px solid #e5ebe7}.scope-dialog>footer span{flex:1}@media(max-width:760px){.scope-columns{grid-template-columns:1fr}.scope-columns>section{max-height:220px;border-right:0;border-bottom:1px solid #edf1ee}}
</style>
