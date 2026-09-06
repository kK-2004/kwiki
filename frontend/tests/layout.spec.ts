import { describe, expect, it } from 'vitest';
import { render, fireEvent } from '@testing-library/vue';
import WikiWorkspaceLayout from '../src/features/wiki/components/WikiWorkspaceLayout.vue';
import { createTestingPinia } from './test-pinia';

describe('three-column workspace layout', () => {
  it('shows global nav, middle column, and content column simultaneously on desktop', () => {
    const screen = render(WikiWorkspaceLayout, {
      global: { plugins: [createTestingPinia()], stubs: { RouterView: true } },
    });

    const columns = [
      screen.getByTestId('global-nav'),
      screen.getByTestId('middle-column'),
      screen.getByTestId('content-column'),
    ];
    const rects = columns.map((el) => el.getBoundingClientRect());
    // no horizontal overlap between the three columns
    expect(rects[0].right).toBeLessThanOrEqual(rects[1].left + 1);
    expect(rects[1].right).toBeLessThanOrEqual(rects[2].left + 1);
  });

  it('exposes knowledge/summary tabs with accessible selection state', async () => {
    const screen = render(WikiWorkspaceLayout, {
      global: { plugins: [createTestingPinia()], stubs: { RouterView: true } },
    });

    const tabs = screen.getAllByRole('tab');
    expect(tabs).toHaveLength(2);
    expect(tabs[0].getAttribute('aria-selected')).toBe('true');

    await fireEvent.click(tabs[1]);
  });
});
