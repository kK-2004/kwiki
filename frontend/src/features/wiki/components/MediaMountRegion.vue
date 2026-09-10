<template>
  <div ref="hostRef" v-html="markerHtml"></div>
</template>

<script setup lang="ts">
/**
 * Renders sanitized markdown HTML while mounting real media viewer instances
 * onto its media nodes: markers created by mediaHtmlToMarkers are reconciled
 * against mounted Vue apps, so every media has exactly one live viewer and
 * players are destroyed when their node disappears. Vue owns instance
 * lifecycle; no player markup is string-concatenated into v-html.
 */
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { createApp, type App } from 'vue';
import MediaBlock from './MediaBlock.vue';
import { markerKeyOf, mediaFromMarker, mediaHtmlToMarkers } from './mediaMount';
import type { MediaSourceResolver } from '@kk-2004/ui-components/components/KMediaViewer';

const props = defineProps<{
  html: string;
  resolver?: MediaSourceResolver;
  kbId?: number;
}>();

const hostRef = ref<HTMLElement | null>(null);
const markerHtml = computed(() => mediaHtmlToMarkers(props.html));

interface MountEntry {
  app: App;
  key: string;
}
const mounts = new Map<Element, MountEntry>();

function reconcile() {
  const host = hostRef.value;
  if (!host) return;
  const markers = Array.from(host.querySelectorAll<HTMLElement>('.kwiki-media-mount'));
  const alive = new Set<Element>();
  for (const marker of markers) {
    alive.add(marker);
    const key = markerKeyOf(marker);
    const existing = mounts.get(marker);
    if (existing?.key === key) continue;
    if (existing) existing.app.unmount();
    const media = mediaFromMarker(marker);
    if (!media) continue;
    const app = createApp(MediaBlock, { media, resolver: props.resolver, kbId: props.kbId });
    app.mount(marker);
    mounts.set(marker, { app, key });
  }
  for (const [element, entry] of Array.from(mounts)) {
    if (!alive.has(element) || !element.isConnected) {
      entry.app.unmount();
      mounts.delete(element);
    }
  }
}

watch(markerHtml, () => { void nextTick(reconcile); }, { immediate: true });

onBeforeUnmount(() => {
  for (const [, entry] of mounts) entry.app.unmount();
  mounts.clear();
});
</script>
