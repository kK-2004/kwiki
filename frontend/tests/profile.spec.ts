import { describe, expect, it } from 'vitest';
import { render, fireEvent } from '@testing-library/vue';
import { createRouter, createMemoryHistory } from 'vue-router';
import ProfilePage from '../src/features/workspace/ProfilePage.vue';
import { useAuthStore } from '../src/features/auth/store';
import { createTestingPinia } from './test-pinia';

describe('个人主页', () => {
  it('在 /users/me 展示资料，并打开头像与密码设置弹窗', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/users/:userId?', name: 'profile', component: ProfilePage }, { path: '/login', name: 'login', component: ProfilePage }],
    });
    await router.push('/users/me');
    await router.isReady();
    const pinia = createTestingPinia();
    const auth = useAuthStore(pinia);
    auth.user = { id: 7, username: 'kk', displayName: 'kk', email: '', admin: false };
    auth.token = 'test-token';

    const screen = render(ProfilePage, { global: { plugins: [pinia, router] } });
    expect(screen.getByRole('heading', { name: '个人设置' })).toBeTruthy();
    expect(screen.getByDisplayValue('kk')).toBeTruthy();

    await fireEvent.click(screen.getByRole('button', { name: '更换头像' }));
    expect(screen.getByRole('dialog', { name: '更换头像' })).toBeTruthy();
    await fireEvent.click(screen.getByRole('button', { name: '取消' }));

    await fireEvent.click(screen.getByRole('button', { name: '修改密码' }));
    expect(screen.getByRole('dialog', { name: '修改密码' })).toBeTruthy();
    await fireEvent.update(screen.getByLabelText('新密码'), 'Kwiki1234');
    await fireEvent.update(screen.getByLabelText('确认新密码'), 'Kwiki1234');
    await fireEvent.click(screen.getByRole('button', { name: '确认修改' }));
    expect(screen.getByRole('status').textContent).toContain('密码已更新');
  });
});
