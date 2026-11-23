import { describe, expect, it, vi } from 'vitest';
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

  it('keeps delivering a batch after one message fails', () => {
    const target: Parameters<typeof installReceiveChannel>[0] = {};
    const received: unknown[] = [];
    const error = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    installReceiveChannel(target, (message) => {
      if (message === 'bad') throw new Error('Invalid native database context');
      received.push(message);
    });
    target.__varroReceiveBatch?.(['first', 'bad', 'api/response']);
    expect(received).toEqual(['first', 'api/response']);
    expect(error).toHaveBeenCalledTimes(1);
    error.mockRestore();
  });
});
