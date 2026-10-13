<script setup lang="ts">
import { KNOWN_EXTRA_KEYS, type ExtraRow } from "../modelConfig";

/** 扩展配置的键值编辑器：可空、可多项；值按 JSON 解析（数字/布尔/对象），其余视为字符串。 */
const rows = defineModel<ExtraRow[]>({ required: true });
defineProps<{ inherited?: ExtraRow[] }>();

function add(key = "") {
  rows.value = [...rows.value, { key, value: "" }];
}

function remove(index: number) {
  rows.value = rows.value.filter((_, i) => i !== index);
}
</script>

<template>
  <div class="extra-editor">
    <div v-if="inherited?.length" class="inherited">
      <span class="muted">继承自模型：</span>
      <el-tag v-for="row in inherited" :key="row.key" size="small" type="info" class="tag">{{ row.key }} = {{ row.value }}</el-tag>
    </div>
    <div v-for="(row, index) in rows" :key="index" class="row">
      <el-input v-model="row.key" placeholder="参数名，如 top_p" :aria-label="`参数名 ${index + 1}`" class="key" />
      <el-input v-model="row.value" placeholder="值，如 0.8 / true / {&quot;a&quot;:1}" :aria-label="`参数值 ${index + 1}`" class="value" />
      <el-button text type="danger" :aria-label="`删除参数 ${index + 1}`" @click="remove(index)">删除</el-button>
    </div>
    <div class="actions">
      <el-button size="small" @click="add()">添加参数</el-button>
      <el-button v-for="(hint, key) in KNOWN_EXTRA_KEYS" :key="key" size="small" text :title="hint" @click="add(String(key))">+ {{ key }}</el-button>
    </div>
    <p class="hint">
      <code>batchSize</code>、<code>timeoutMs</code> 由 kwiki 解释；其余参数原样作为请求体字段发给 provider（如 <code>top_p</code>、<code>seed</code>、<code>enable_search</code>、embedding 的 <code>text_type</code>）。
      <code>model</code>、<code>messages</code>、<code>temperature</code>、<code>max_tokens</code>、<code>enable_thinking</code> 等请使用专用字段。
    </p>
  </div>
</template>

<style scoped>
.extra-editor { display: flex; flex-direction: column; gap: 8px; width: 100%; }
.row { display: grid; grid-template-columns: minmax(0, 2fr) minmax(0, 3fr) auto; gap: 8px; align-items: center; }
.actions { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.inherited { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; font-size: 12px; }
.tag { margin: 0; }
.hint { margin: 0; font-size: 12px; color: var(--k-muted); line-height: 1.6; }
.muted { color: var(--k-muted); }
</style>
