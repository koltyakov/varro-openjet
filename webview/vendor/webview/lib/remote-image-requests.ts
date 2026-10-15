import type { RemoteImageResponse } from '../../shared/remote-images';
import { VARRO_API_ENDPOINTS } from '../../shared/protocol';
import { apiCall } from './bridge';

type ImageRequest = {
  source: string;
  signal: AbortSignal;
  automatic: boolean;
};

type QueuedImageRequest = ImageRequest & {
  resolve: (image: RemoteImageResponse) => void;
  // oxlint-disable-next-line anti-slop/no-unknown-parameters -- This native Promise rejection boundary forwards arbitrary transport/abort errors to the caller's catch handler.
  reject: (reason: unknown) => void;
  cleanup: () => void;
};

/** Share the host's two-download budget between automatic and Load all requests. */
export class RemoteImageRequests {
  private readonly queue: QueuedImageRequest[] = [];
  private active = 0;

  constructor(
    private readonly request: (input: ImageRequest) => Promise<RemoteImageResponse> = (input) =>
      apiCall<RemoteImageResponse>(
        'POST',
        VARRO_API_ENDPOINTS.remoteImage,
        input.automatic ? { url: input.source, automatic: true } : { url: input.source },
        { signal: input.signal, retries: 0, timeoutMs: 20_000 }
      )
  ) {}

  load(source: string, signal: AbortSignal, automatic = false): Promise<RemoteImageResponse> {
    return new Promise((resolve, reject) => {
      signal.throwIfAborted();
      const abort = () => {
        const index = this.queue.indexOf(entry);
        if (index >= 0) this.queue.splice(index, 1);
        entry.cleanup();
        reject(signal.reason);
      };
      const entry: QueuedImageRequest = {
        source,
        signal,
        automatic,
        resolve,
        reject,
        cleanup: () => signal.removeEventListener('abort', abort),
      };
      signal.addEventListener('abort', abort, { once: true });
      this.queue.push(entry);
      this.pump();
    });
  }

  private pump() {
    while (this.active < 2 && this.queue.length > 0) {
      const entry = this.queue.shift()!;
      if (entry.signal.aborted) {
        entry.cleanup();
        entry.reject(entry.signal.reason);
        continue;
      }
      this.active += 1;
      void this.request(entry)
        .then(entry.resolve, entry.reject)
        .finally(() => {
          entry.cleanup();
          this.active -= 1;
          this.pump();
        });
    }
  }
}

export const remoteImageRequests = new RemoteImageRequests();
