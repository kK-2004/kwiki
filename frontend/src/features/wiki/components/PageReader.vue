<template>
  <article v-if="page" data-testid="page-reader" class="reader">
    <a v-if="parentTitle" class="back" href="#" @click.prevent="selectParent">
      <i class="i-lucide-arrow-left" aria-hidden="true"></i>{{ parentTitle }}
    </a>
    <div class="title-row">
      <div class="title-copy">
        <h1 class="page-title">{{ displayTitle }}</h1>
        <p class="meta" data-testid="page-meta">
          <span class="pill"><i class="i-lucide-file-text" aria-hidden="true"></i>页面</span>
          <span class="pill">r{{ page.revisionNo }}</span>
          <span class="pill">已发布</span>
          <span class="updated"><i class="i-lucide-clock" aria-hidden="true"></i>更新于 {{ page.createdAt }}</span>
        </p>
      </div>
    </div>
    <div class="markdown article" data-testid="page-content" v-html="sanitizedHtml"></div>
    <footer v-if="(backlinks ?? []).length" class="provenance" data-testid="page-backlinks">
      <span>被链接</span>
      <div class="prov-links">
        <a v-for="link in backlinks" :key="link.id" class="link" href="#">{{ link.title }}</a>
      </div>
    </footer>
  </article>
  <p v-else-if="error" class="state" data-testid="page-error">{{ errorText }}</p>
  <p v-else class="state" data-testid="page-empty">请选择左侧页面</p>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useWikiStore, normalizedError } from '../store';
import type { TreeNodeDto } from '../api';

const props = defineProps<{
  breadcrumb?: string;
  title?: string;
  backlinks?: Array<{ id: number; title: string }>;
}>();

const store = useWikiStore();
const page = computed(() => store.page);
const error = computed(() => store.pageError);
const errorText = computed(() => normalizedError(error.value));

/** Server-rendered HTML is already sanitized by MarkdownPort. */
const sanitizedHtml = computed(() => page.value?.html ?? '');

/** Title and path fall back to the tree entry of the selected page. */
function findPath(nodes: TreeNodeDto[], id: number, trail: TreeNodeDto[]): TreeNodeDto[] | null {
  for (const node of nodes) {
    const next = [...trail, node];
    if (node.id === id) {
      return next;
    }
    const found = findPath(node.children ?? [], id, next);
    if (found) {
      return found;
    }
  }
  return null;
}
const selectedPath = computed(() =>
  store.selectedPageId == null ? null : findPath(store.tree, store.selectedPageId, []),
);
const displayTitle = computed(() => props.title ?? selectedPath.value?.at(-1)?.title ?? '');
const parentTitle = computed(() => {
  const path = selectedPath.value;
  if (!path || path.length < 2) {
    return null;
  }
  return path.at(-2)?.title ?? null;
});

function selectParent() {
  const path = selectedPath.value;
  if (!path || path.length < 2) {
    return;
  }
  const parent = path.at(-2);
  if (parent) {
    store.selectPage(parent.nodeType === 'PAGE' ? parent.id : null);
  }
}
</script>

<style scoped>
.reader {
  color: var(--kwiki-ink);
}
.back {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-bottom: 12px;
  color: #7f8585;
  text-decoration: none;
  font-size: 12px;
}
.back i {
  font-size: 13px;
}
.back:hover {
  color: var(--kwiki-green-dark);
}
.title-row {
  display: flex;
  align-items: flex-start;
  gap: 20px;
}
.title-copy {
  min-width: 0;
  flex: 1;
}
.page-title {
  margin: 0 0 9px;
  font-size: 29px;
  line-height: 1.15;
  letter-spacing: -0.025em;
}
.meta {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin: 0;
  color: #8f9696;
  font-size: 12px;
}
.pill {
  padding: 3px 7px;
  border-radius: 5px;
  background: #f3f5f4;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.pill i {
  font-size: 12px;
}
.updated {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #a4aaaa;
}
.updated i {
  font-size: 12px;
}
.markdown.article {
  font-size: 15px;
  line-height: 1.85;
  color: #343a3a;
}
.markdown :deep(h1) {
  font-size: 25px;
  margin: 27px 0 9px;
  letter-spacing: -0.02em;
}
.markdown :deep(h2) {
  margin: 27px 0 9px;
  font-size: 19px;
}
.markdown :deep(h3) {
  margin: 23px 0 8px;
  font-size: 17px;
}
.markdown :deep(p) {
  margin: 9px 0;
}
.markdown :deep(ol),
.markdown :deep(ul) {
  margin: 7px 0 12px;
  padding-left: 24px;
}
.markdown :deep(li) {
  margin: 4px 0;
}
.markdown :deep(a) {
  color: var(--kwiki-green-dark);
  text-decoration: none;
  border-bottom: 1px dashed #5ed19a;
}
.markdown :deep(table) {
  border-collapse: separate;
  border-spacing: 0;
  margin: 12px 0 16px;
  border: 1px solid #e0e4e3;
  border-radius: 7px;
  overflow: hidden;
  font-size: 14px;
}
.markdown :deep(th),
.markdown :deep(td) {
  min-width: 88px;
  padding: 8px 12px;
  border-right: 1px solid #e6e9e8;
  border-bottom: 1px solid #e6e9e8;
  text-align: left;
}
.markdown :deep(th) {
  background: var(--kwiki-soft);
}
.markdown :deep(tr:last-child td) {
  border-bottom: 0;
}
.markdown :deep(th:last-child),
.markdown :deep(td:last-child) {
  border-right: 0;
}
.markdown :deep(pre) {
  background: var(--kwiki-soft);
  padding: 12px 14px;
  border-radius: 7px;
  overflow: auto;
  font: 13px/1.75 ui-monospace, SFMono-Regular, Menlo, monospace;
}
.markdown :deep(code) {
  background: var(--kwiki-soft);
  border-radius: 4px;
  padding: 1px 5px;
  font: 13px ui-monospace, SFMono-Regular, Menlo, monospace;
}
.markdown :deep(pre code) {
  background: none;
  padding: 0;
}
.provenance {
  margin-top: 32px;
  padding-top: 17px;
  border-top: 1px solid var(--kwiki-line);
  display: grid;
  grid-template-columns: 70px 1fr;
  gap: 10px 8px;
  color: #979d9d;
  font-size: 12px;
}
.prov-links {
  display: flex;
  flex-wrap: wrap;
  gap: 9px 18px;
}
.link {
  color: var(--kwiki-green-dark);
  text-decoration: none;
  border-bottom: 1px dashed #5ed19a;
}
.state {
  color: var(--kwiki-muted);
  line-height: 1.7;
}
@media (max-width: 640px) {
  .title-row {
    display: block;
  }
  .page-title {
    font-size: 25px;
  }
  .provenance {
    grid-template-columns: 1fr;
  }
}
</style>
