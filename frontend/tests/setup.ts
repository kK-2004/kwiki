import { cleanup } from '@testing-library/vue';
import { afterEach, vi } from 'vitest';

afterEach(cleanup);

// jsdom 未实现 Blob URL API：提供可被 spyOn 覆盖的确定性桩。
if (typeof URL.createObjectURL !== 'function') {
  Object.defineProperty(URL, 'createObjectURL', {
    configurable: true,
    writable: true,
    value: vi.fn(() => `blob:stub-${Math.random().toString(36).slice(2)}`),
  });
}
if (typeof URL.revokeObjectURL !== 'function') {
  Object.defineProperty(URL, 'revokeObjectURL', {
    configurable: true,
    writable: true,
    value: vi.fn(),
  });
}
