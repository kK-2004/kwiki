import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/vue';
import RetrievalActivity from '../src/features/wiki/components/RetrievalActivity.vue';
import type { ActivityStep } from '../src/features/wiki/sse';

const plan: ActivityStep = {
  stepId: 'q1-plan', phase: 'PLAN', status: 'COMPLETED', startedAt: 1000, queryRound: 1, durationMs: 800,
  summary: '已规划：search_chunks、rewrite_query',
};

describe('plan phase', () => {
  it('shows a Chinese label for the planning step instead of the raw enum name', () => {
    const view = render(RetrievalActivity, { props: { steps: [plan], running: true, failed: false } });
    expect(view.getByText('规划检索策略')).toBeTruthy();
    expect(view.queryByText('PLAN')).toBeNull();
    // 运行态下摘要会同时出现在顶部状态栏和时间线步骤里
    expect(view.getAllByText('已规划：search_chunks、rewrite_query')).toHaveLength(2);
  });
});
