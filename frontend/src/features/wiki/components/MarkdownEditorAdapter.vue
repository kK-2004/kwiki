<template>
  <textarea
    :value="modelValue"
    aria-label="Markdown 编辑器"
    class="markdown-editor"
    data-testid="markdown-editor"
    @input="onInput"
  ></textarea>
</template>

<script setup lang="ts">
/**
 * Markdown round-trip editor adapter: source-in, source-out, no lossy transforms.
 * A richer visual editor can replace this component behind the same contract
 * (fixture tests pin the round-trip behavior, not the widget).
 */
defineProps<{ modelValue: string }>();
const emit = defineEmits<{ 'update:modelValue': [value: string] }>();

function onInput(event: Event) {
  emit('update:modelValue', (event.target as HTMLTextAreaElement).value);
}
</script>

<style scoped>
.markdown-editor {
  width: 100%;
  min-height: 400px;
  font: 13px/1.75 ui-monospace, SFMono-Regular, Menlo, monospace;
  border: 0;
  outline: none;
  resize: vertical;
  padding: 22px;
  background: var(--kwiki-panel);
  color: var(--kwiki-text);
}
</style>
