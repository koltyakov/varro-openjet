import { createInterface } from 'node:readline';
import { ProviderLimitService } from '../vendor/extension/provider-limit-service';
import { asRecord } from '../vendor/shared/type-utils';

// stdout is a private JSON-lines channel to the Kotlin host. Credentials are
// read by the adapters or returned over this pipe, never put in process args.
function send(message: unknown) {
  process.stdout.write(JSON.stringify(message) + '\n');
}

let nextId = 0;
const requests = new Map<number, {
  resolve: (value: unknown) => void;
  reject: (error: Error) => void;
  timer: ReturnType<typeof setTimeout>;
}>();

const service = new ProviderLimitService({
  request(method, path, body, options) {
    const id = ++nextId;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        requests.delete(id);
        reject(new Error('OpenCode quota request timed out'));
      }, 35_000);
      requests.set(id, { resolve, reject, timer });
      send({ type: 'server-request', id, method, path, body, directory: options?.directory });
    });
  },
}, undefined, undefined, (update) => send({ type: 'updated', update }));

async function receive(line: string) {
  const message = asRecord(JSON.parse(line));
  if (!message) throw new Error('Invalid quota message');
  if (message.type === 'server-response' && typeof message.id === 'number') {
    const request = requests.get(message.id);
    if (!request) return;
    requests.delete(message.id);
    clearTimeout(request.timer);
    if (typeof message.error === 'string') request.reject(new Error(message.error));
    else request.resolve(message.data);
    return;
  }
  if (message.type === 'clear') {
    service.clearCache();
    return;
  }
  if (message.type !== 'get' || typeof message.id !== 'number' ||
      typeof message.providerID !== 'string' || !message.providerID.trim() ||
      !(message.modelID == null || typeof message.modelID === 'string') ||
      !(message.directory == null || typeof message.directory === 'string')) {
    throw new Error('Invalid quota request');
  }
  const data = await service.get(message.providerID, message.modelID ?? null, message.directory ?? undefined);
  send({ type: 'result', id: message.id, data });
}

const input = createInterface({ input: process.stdin, crlfDelay: Infinity });
input.on('line', (line) => {
  void receive(line).catch(() => {
    // Do not echo malformed payloads or provider errors into IDE logs.
    send({ type: 'fatal', error: 'Provider quota helper protocol failed' });
    shutdown(1);
  });
});

function shutdown(code: number) {
  service.dispose();
  for (const request of requests.values()) clearTimeout(request.timer);
  requests.clear();
  process.exit(code);
}

input.on('close', () => shutdown(0));
process.on('SIGTERM', () => shutdown(0));
send({ type: 'ready' });
