<template>
  <div class="admin-shell">
    <aside class="side" aria-label="管理后台导航">
      <div class="brand"><span class="logo">k</span><strong>KWiki</strong><small>管理后台</small></div>
      <nav>
        <RouterLink to="/" class="nav-item" exact-active-class="active">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6h16M4 12h16M4 18h10" /></svg>全局索引
        </RouterLink>
        <RouterLink to="/gray-releases" class="nav-item" active-class="active">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 18h4v-6H4zM10 18h4V6h-4zM16 18h4v-9h-4z" /></svg>灰度发布
        </RouterLink>
        <RouterLink to="/knowledge-graphs" class="nav-item" active-class="active">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 6a2 2 0 1 0 0 .01M18 6a2 2 0 1 0 0 .01M12 18a2 2 0 1 0 0 .01M7.5 7.5l3.5 8.5M16.5 7.5 13 16M8 6h8" /></svg>知识图谱
        </RouterLink>
      </nav>
      <footer>
        <ThemeSwitcher />
        <div class="account">
          <span class="avatar">{{ (auth.user?.username || "?").slice(0, 1).toUpperCase() }}</span>
          <span class="name">{{ auth.user?.username }}</span>
          <button type="button" class="logout" @click="logout">退出</button>
        </div>
      </footer>
    </aside>
    <div class="content"><slot /></div>
  </div>
</template>

<script setup lang="ts">
import { RouterLink, useRouter } from "vue-router";
import { useAuth } from "../auth";
import ThemeSwitcher from "../theme/ThemeSwitcher.vue";

const auth = useAuth();
const router = useRouter();
function logout() {
  auth.logout();
  void router.push("/login");
}
</script>

<style scoped>
.admin-shell {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 224px minmax(0, 1fr);
}

.side {
  position: sticky;
  top: 0;
  height: 100vh;
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding: 18px 12px 12px;
  background: var(--k-surface);
  border-right: 1px solid var(--k-line);
}

.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 6px;
}

.brand strong {
  font-size: 17px;
  font-weight: 600;
  letter-spacing: -0.4px;
}

.brand small {
  padding: 1px 6px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r-pill);
  color: var(--k-muted);
  font-size: 11px;
}

.logo {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border-radius: var(--k-r-sm);
  background: var(--k-green);
  color: var(--k-canvas);
  font-weight: 700;
}

nav {
  display: grid;
  gap: 2px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 34px;
  padding: 6px 10px;
  border: 1px solid transparent;
  border-radius: var(--k-r);
  color: var(--k-ink-2);
  text-decoration: none;
  font-size: 13.5px;
}

.nav-item:hover {
  background: var(--k-surface-hover);
  color: var(--k-ink);
}

.nav-item.active {
  background: var(--k-selected);
  border-color: var(--k-line);
  box-shadow: var(--k-shadow-sm);
  color: var(--k-ink);
  font-weight: 600;
}

.nav-item svg {
  width: 16px;
  height: 16px;
  fill: none;
  stroke: var(--k-muted);
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.nav-item.active svg {
  stroke: var(--k-green-deep);
}

footer {
  margin-top: auto;
  display: grid;
  gap: 10px;
  padding-top: 12px;
  border-top: 1px solid var(--k-line);
}

.account {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 4px;
}

.avatar {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: var(--k-green-soft);
  color: var(--k-green-deep);
  font-size: 12px;
  font-weight: 700;
}

.name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}

.logout {
  border: 0;
  background: transparent;
  color: var(--k-muted);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}

.logout:hover {
  color: var(--k-danger);
}

.content {
  min-width: 0;
}

@media (max-width: 900px) {
  .admin-shell {
    grid-template-columns: minmax(0, 1fr);
  }

  .side {
    position: static;
    height: auto;
    flex-direction: row;
    flex-wrap: wrap;
    align-items: center;
  }

  nav {
    grid-auto-flow: column;
  }

  footer {
    margin: 0 0 0 auto;
    border: 0;
    padding: 0;
    grid-auto-flow: column;
  }
}
</style>
