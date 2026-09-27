<template>
  <main class="auth-page">
    <section class="auth-card" aria-labelledby="auth-title">
      <div class="brand"><span class="logo">k</span><strong>kwiki</strong></div>
      <h1 id="auth-title">{{ isRegister ? '创建 kwiki 账号' : '登录 kwiki' }}</h1>
      <p class="sub">{{ isRegister ? '用真实账号开始管理知识。' : '登录后访问你的知识库与会话。' }}</p>
      <form @submit.prevent="submit">
        <label>用户名<input v-model.trim="username" autocomplete="username" required minlength="2" /></label>
        <label v-if="isRegister">显示名称<input v-model.trim="displayName" required /></label>
        <label v-if="isRegister">邮箱（可选）<input v-model.trim="email" type="email" autocomplete="email" /></label>
        <label>密码<div class="password-field"><input v-model="password" :type="showPassword ? 'text' : 'password'" :autocomplete="isRegister ? 'new-password' : 'current-password'" required :minlength="isRegister ? 6 : undefined" /><button class="password-toggle" type="button" :aria-label="showPassword ? '隐藏密码' : '显示密码'" :title="showPassword ? '隐藏密码' : '显示密码'" @click="showPassword = !showPassword"><svg v-if="showPassword" viewBox="0 0 24 24" aria-hidden="true"><path d="m3 3 18 18M10.6 10.6a2 2 0 0 0 2.8 2.8M9.9 4.2A10.7 10.7 0 0 1 12 4c5.2 0 8.8 4 10 8a12.8 12.8 0 0 1-2.1 4.1M6.6 6.6C4.2 8.1 2.7 10.4 2 12c1.2 4 4.8 8 10 8 1 0 1.9-.1 2.8-.4" /></svg><svg v-else viewBox="0 0 24 24" aria-hidden="true"><path d="M2 12s3.6-8 10-8 10 8 10 8-3.6 8-10 8S2 12 2 12Z" /><circle cx="12" cy="12" r="3" /></svg></button></div></label>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <button class="submit" type="submit" :disabled="auth.busy">
          {{ auth.busy ? '提交中…' : isRegister ? '注册并进入' : '登录' }}
        </button>
      </form>
      <RouterLink class="switch" :to="isRegister ? { name: 'login', query: route.query } : { name: 'register', query: route.query }">
        {{ isRegister ? '已有账号？登录' : '还没有账号？注册' }}
      </RouterLink>
    </section>
  </main>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { RouterLink, useRoute, useRouter } from 'vue-router';
import { useAuthStore } from './store';

const props = defineProps<{ mode: 'login' | 'register' }>();
const auth = useAuthStore();
const route = useRoute();
const router = useRouter();
const isRegister = computed(() => props.mode === 'register');
const username = ref('');
const displayName = ref('');
const email = ref('');
const password = ref('');
const showPassword = ref(false);
const error = computed(() => auth.error);

async function submit() {
  try {
    if (isRegister.value) await auth.register(username.value, displayName.value, email.value, password.value);
    else await auth.login(username.value, password.value);
    const target = typeof route.query.returnTo === 'string' && route.query.returnTo.startsWith('/')
      ? route.query.returnTo : '/knowledge-bases';
    await router.replace(target);
  } catch { /* 状态仓库（store）会对外暴露一条稳定的错误信息 */ }
}
</script>

<style scoped>
.auth-page {
  min-height: 100dvh;
  display: grid;
  place-items: center;
  padding: 24px;
  background:
    radial-gradient(1200px 600px at 20% -10%, var(--k-auth-sky), transparent 60%),
    radial-gradient(900px 500px at 100% 110%, var(--k-auth-sand), transparent 60%),
    var(--k-canvas);
  color: var(--k-ink);
}

.auth-card {
  width: min(400px, 100%);
  padding: 32px;
  border: 1px solid var(--k-line);
  border-radius: 16px;
  background: var(--k-canvas);
  box-shadow: var(--k-shadow-float);
}

.brand {
  display: flex;
  align-items: center;
  gap: 9px;
  font-size: 18px;
  color: var(--k-ink);
}

.logo {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: var(--k-r-sm);
  background: var(--k-green);
  /* 浅色下为白字、深色下为近黑字，两种主题在绿底上都清晰 */
  color: var(--k-canvas);
  font-weight: 700;
}

.auth-card h1 {
  margin: 20px 0 6px;
  font-size: 24px;
  font-weight: 600;
  letter-spacing: -0.5px;
  color: var(--k-ink);
}

.sub {
  margin: 0 0 24px;
  color: var(--k-muted);
}

form {
  display: grid;
  gap: 15px;
}

label {
  display: grid;
  gap: 7px;
  color: var(--k-ink-2);
  font-size: 13px;
  font-weight: 500;
}

input {
  width: 100%;
  box-sizing: border-box;
  height: 40px;
  padding: 0 12px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r);
  background: var(--k-canvas);
  font: inherit;
  color: var(--k-ink);
  outline: none;
}

input:hover {
  border-color: var(--k-line-strong);
}

input:focus {
  border-color: var(--k-green);
  box-shadow: var(--k-focus-ring);
}

.password-field {
  position: relative;
  width: 100%;
}

.password-field input {
  padding-right: 44px;
}

.password-toggle {
  position: absolute;
  top: 50%;
  right: 9px;
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: var(--k-r-sm);
  transform: translateY(-50%);
  background: transparent;
  color: var(--k-muted);
  cursor: pointer;
}

.password-toggle:hover {
  background: var(--k-surface-hover);
  color: var(--k-ink);
}

.password-toggle:focus-visible {
  outline: 2px solid var(--k-green);
  outline-offset: 1px;
}

.password-toggle svg {
  width: 18px;
  height: 18px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.8;
}

.submit {
  width: 100%;
  min-height: 40px;
  border: 0;
  border-radius: var(--k-r);
  background: var(--k-primary);
  color: var(--k-on-primary);
  font: inherit;
  font-weight: 600;
  cursor: pointer;
}

.submit:hover:not(:disabled) {
  background: var(--k-primary-hover);
}

.submit:disabled {
  opacity: 0.55;
  cursor: wait;
}

.error {
  margin: 0;
  color: var(--k-danger);
}

.switch {
  display: block;
  margin-top: 19px;
  color: var(--k-green-deep);
  text-align: center;
  text-decoration: none;
  font-size: 13px;
}
</style>
