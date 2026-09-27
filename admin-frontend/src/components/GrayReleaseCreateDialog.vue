<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { grayErrorMessage } from "../grayErrors";
import { api, type ParserOption } from "../api";

/** occupied：已在其他未结束灰度中的知识库 → 所在灰度名称 */
const props = defineProps<{ modelValue: boolean; occupied: Record<number, string> }>();
const emit = defineEmits<{ "update:modelValue": [value: boolean]; created: [] }>();

const parsers = ref<ParserOption[]>([]);
const bases = ref<{ id: number; name: string }[]>([]);
const parserVersion = ref("");
const selected = ref<number[]>([]);
const name = ref("");
const keyword = ref("");
const saving = ref(false);

const filtered = computed(() => {
  const word = keyword.value.trim().toLowerCase();
  return word ? bases.value.filter(base => base.name.toLowerCase().includes(word) || String(base.id) === word) : bases.value;
});

async function load() {
  [parsers.value, bases.value] = await Promise.all([api.parsers(), api.adminKnowledgeBases()]);
  parserVersion.value = parsers.value.find(parser => parser.available && parser.id !== "kwiki-parse-1")?.id ?? "";
  selected.value = [];
  name.value = "";
  keyword.value = "";
}

watch(() => props.modelValue, open => { if (open) void load(); }, { immediate: true });

async function submit() {
  saving.value = true;
  try {
    await api.createGrayRelease({ name: name.value.trim() || undefined, parserVersion: parserVersion.value, kbIds: selected.value });
    ElMessage.success("灰度已创建，点击「开始同步」构建灰度索引");
    emit("update:modelValue", false);
    emit("created");
  } catch (error) {
    ElMessage.error(grayErrorMessage(error, "创建失败"));
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <el-dialog :model-value="modelValue" title="新建灰度" width="620" @update:model-value="emit('update:modelValue', $event)">
    <el-form label-position="top">
      <el-form-item label="解析器">
        <el-radio-group v-model="parserVersion" class="parser-group">
          <el-radio v-for="parser in parsers" :key="parser.id" :value="parser.id" :disabled="!parser.available" border>
            {{ parser.label }}<span class="parser-id">{{ parser.id }}</span>
          </el-radio>
        </el-radio-group>
        <p v-for="parser in parsers.filter(item => !item.available)" :key="parser.id" class="hint">{{ parser.unavailableReason }}</p>
      </el-form-item>
      <el-form-item :label="`知识库（已选 ${selected.length}）`">
        <el-input v-model="keyword" placeholder="搜索知识库" clearable />
        <el-checkbox-group v-model="selected" class="kb-list">
          <el-checkbox v-for="base in filtered" :key="base.id" :value="base.id" :disabled="Boolean(occupied[base.id])">
            {{ base.name }}<span v-if="occupied[base.id]" class="occupied">已在「{{ occupied[base.id] }}」中</span>
          </el-checkbox>
          <p v-if="!filtered.length" class="hint">没有匹配的知识库</p>
        </el-checkbox-group>
      </el-form-item>
      <el-form-item label="名称（可选）">
        <el-input v-model="name" placeholder="默认：解析器名 + 灰度编号" maxlength="120" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="!parserVersion || !selected.length" @click="submit">创建灰度</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.parser-group { display: flex; gap: 8px; flex-wrap: wrap; }
.parser-id { margin-left: 6px; color: var(--k-faint); font-size: 12px; }
.kb-list { display: grid; gap: 2px; width: 100%; max-height: 280px; margin-top: 8px; padding: 6px 10px; overflow: auto; border: 1px solid var(--k-line); border-radius: var(--k-r); }
.occupied { margin-left: 8px; color: var(--k-muted); font-size: 12px; }
.hint { margin: 4px 0 0; color: var(--k-muted); font-size: 12px; }
</style>
