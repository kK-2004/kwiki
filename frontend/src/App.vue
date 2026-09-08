<template>
  <WorkspaceShell v-if="route.meta.auth"><RouterView /></WorkspaceShell>
  <RouterView v-else />
</template>

<script setup lang="ts">
import { onMounted, onBeforeUnmount } from 'vue';
import { RouterView, useRoute } from 'vue-router';
import { useAuthStore } from './features/auth/store';
import { router } from './router';
import WorkspaceShell from './features/workspace/WorkspaceShell.vue';
const route = useRoute();

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
