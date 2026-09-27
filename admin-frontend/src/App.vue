<template>
  <router-view v-if="route.meta.public || !route.matched.length" />
  <AdminLayout v-else><router-view /></AdminLayout>
</template>
<script setup lang="ts">
import { useRoute } from "vue-router";
import AdminLayout from "./components/AdminLayout.vue";
import { useTheme } from "./theme/useTheme";
const route = useRoute();
// 登录页与路由尚未解析完成时不挂 AdminLayout，避免闪出侧边栏；主题在启动时即应用
useTheme();
</script>
