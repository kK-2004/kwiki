import { defineStore } from 'pinia';
import {
  api, bumpAuthGeneration, clearAuthToken, getAuthToken, setAuthToken, type UserDto,
} from '../wiki/api';

interface LoginResponse {
  token: string;
  tokenType: string;
  expiresInSeconds: number;
  user: UserDto;
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: getAuthToken(),
    user: null as UserDto | null,
    ready: false,
    busy: false,
    error: '',
    generation: 0,
  }),
  getters: { authenticated: (state) => Boolean(state.token) },
  actions: {
    accept(response: LoginResponse) {
      this.generation = bumpAuthGeneration();
      setAuthToken(response.token);
      this.token = response.token;
      this.user = response.user;
    },
    async boot() {
      if (!this.token) { this.ready = true; return; }
      try { this.user = await api.json<UserDto>('/auth/me'); }
      catch { this.logout(); }
      finally { this.ready = true; }
    },
    async login(username: string, password: string) {
      this.busy = true; this.error = '';
      try { this.accept(await api.post<LoginResponse>('/auth/login', { username, password })); }
      catch (error) { this.error = errorMessage(error); throw error; }
      finally { this.busy = false; }
    },
    async register(username: string, displayName: string, email: string, password: string) {
      this.busy = true; this.error = '';
      try {
        this.accept(await api.post<LoginResponse>('/auth/register', {
          username, displayName, email: email || undefined, password,
        }));
      } catch (error) { this.error = errorMessage(error); throw error; }
      finally { this.busy = false; }
    },
    logout() {
      clearAuthToken(); this.token = ''; this.user = null; this.ready = true;
      this.generation = bumpAuthGeneration();
    },
  },
});

function errorMessage(error: unknown): string {
  if (typeof error === 'object' && error !== null && 'code' in error) {
    const code = String((error as { code: unknown }).code);
    if (code === 'account_exists') return '用户名或邮箱已存在';
    if (code === 'invalid_credentials' || code === 'unauthenticated') return '用户名或密码错误';
    if (code === 'forbidden') return '当前账号没有执行此操作的权限';
    return code;
  }
  return '请求失败，请稍后重试';
}
