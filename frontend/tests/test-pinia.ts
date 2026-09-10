import { createPinia, setActivePinia } from 'pinia';

/** 每个测试使用全新的 Pinia；状态仓库（store）默认值按各测试分别控制。 */
export function createTestingPinia() {
  const pinia = createPinia();
  setActivePinia(pinia);
  return pinia;
}
