<template>
  <div ref="hostRef" v-html="markerHtml"></div>
</template>

<script setup lang="ts">
/**
 * 渲染经过净化的 Markdown HTML，同时将真实的媒体查看器（viewer）实例
 * 挂载到其媒体节点上：由 mediaHtmlToMarkers 创建的标记会与
 * 已挂载的 Vue 应用协调，使每个媒体恰好对应一个存活的查看器，且
 * 节点消失时播放器被销毁。实例的
 * 生命周期由 Vue 管理；没有任何播放器标记通过字符串拼接进入 v-html。
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
