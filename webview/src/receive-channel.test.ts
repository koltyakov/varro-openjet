import { describe, expect, it } from 'vitest';
import { installReceiveChannel } from './receive-channel';

describe('native receive batches', () => {
  it('preserves individual message ordering and supports legacy deliveries', () => {
    const target: Parameters<typeof installReceiveChannel>[0] = {};
    const received: unknown[] = [];
    installReceiveChannel(target, (message) => received.push(message));
    target.__varroReceive?.('before');
    target.__varroReceiveBatch?.(['delta', 'permission', 'idle']);
    target.__varroReceive?.('after');
    expect(received).toEqual(['before', 'delta', 'permission', 'idle', 'after']);
  });
});
