import { cleanup } from '@testing-library/vue';
import { afterEach, beforeEach, vi } from 'vitest';

// Node 25 默认启用内置 Web Storage，未指定 --localstorage-file 时全局 localStorage 是没有任何方法的空桩，
// 且会遮蔽 jsdom 的 Storage。仅在全局 localStorage 不可用时替换为基于 Map 的内存实现，jsdom 正常时不受影响。
const storageValues = new Map<string, string>();
const memoryStorage = {
  getItem: (key: string) => storageValues.get(key) ?? null,
  setItem: (key: string, value: string) => void storageValues.set(key, String(value)),
  removeItem: (key: string) => void storageValues.delete(key),
  clear: () => storageValues.clear(),
  key: (index: number) => [...storageValues.keys()][index] ?? null,
  get length() {
    return storageValues.size;
  },
};
const needsStorageStub = typeof globalThis.localStorage?.clear !== 'function';
if (needsStorageStub) vi.stubGlobal('localStorage', memoryStorage);
// 用例中的 vi.unstubAllGlobals() 会连同此桩一起撤销，因此每个用例开始前重新安装
beforeEach(() => {
  if (needsStorageStub) vi.stubGlobal('localStorage', memoryStorage);
});

afterEach(() => {
  cleanup();
  if (needsStorageStub) memoryStorage.clear();
});

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
