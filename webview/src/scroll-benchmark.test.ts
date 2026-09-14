import { expect, it } from 'vitest';
import { summarizeFrames } from './scroll-benchmark';

it('reports missing frame evidence without inventing a frame rate', () => {
  expect(summarizeFrames([], [])).toMatchObject({ frames: 0, p95FrameMs: null, maxFrameMs: null, movementPx: 0, finalBottomDistance: null });
});

it('counts stalls, backward steps and sparse row measurements', () => {
  const result = summarizeFrames([
    { time: 0, top: 100, bottom: 100, rows: 40, nodes: 5000 },
    { time: 16, top: 110, bottom: 90 },
    { time: 32, top: 108, bottom: 92, rows: 41 },
    { time: 160, top: 200, bottom: 0 },
  ], [{ start: 40, duration: 110 }]);
  expect(result).toMatchObject({ frames: 4, p50FrameMs: 16, p95FrameMs: 128, maxFrameMs: 128, gapsOver50ms: 1,
    longTasks: 1, maxLongTaskMs: 110, peakRows: 41, peakNodes: 5000, movementPx: 100, finalBottomDistance: 0, backwardsSteps: 1 });
});
