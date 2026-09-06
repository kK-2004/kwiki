import { createPinia, setActivePinia } from 'pinia';

/** Fresh Pinia per test; store state defaults are controlled per test. */
export function createTestingPinia() {
  const pinia = createPinia();
  setActivePinia(pinia);
  return pinia;
}
