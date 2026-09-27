<template>
  <NConfigProvider abstract :theme="resolved === 'dark' ? darkTheme : null" :theme-overrides="themeOverrides">
    <WorkspaceShell v-if="route.meta.auth"><RouterView /></WorkspaceShell>
    <RouterView v-else />
  </NConfigProvider>
</template>

<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount } from 'vue';
import { RouterView, useRoute } from 'vue-router';
import { NConfigProvider, darkTheme, type GlobalThemeOverrides } from 'naive-ui';
import { useAuthStore } from './features/auth/store';
import { router } from './router';
import { useTheme } from './theme/useTheme';
import WorkspaceShell from './features/workspace/WorkspaceShell.vue';
const route = useRoute();
const { resolved } = useTheme();
// Naive UI 不读取 CSS 变量，这里按当前主题给出与令牌一致的具体值
const themeOverrides = computed<GlobalThemeOverrides>(() => ({
  common: {
    primaryColor: resolved.value === 'dark' ? '#34d399' : '#059669',
    primaryColorHover: resolved.value === 'dark' ? '#6ee7b7' : '#10b981',
    borderRadius: '8px',
    fontFamily: "Inter, ui-sans-serif, -apple-system, 'PingFang SC', 'Microsoft YaHei', sans-serif",
  },
}));

const auth = useAuthStore();
function onUnauthenticated() {
  const returnTo = router.currentRoute.value.fullPath;
  auth.logout();
  if (router.currentRoute.value.name !== 'login') void router.replace({ name: 'login', query: { returnTo } });
}
onMounted(() => {
  void auth.boot();
  window.addEventListener('kwiki:unauthenticated', onUnauthenticated);
});
onBeforeUnmount(() => window.removeEventListener('kwiki:unauthenticated', onUnauthenticated));
</script>
