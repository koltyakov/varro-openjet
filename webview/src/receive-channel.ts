/** Keep native batches ordered while preserving the upstream one-message event contract. */
export function installReceiveChannel(
  target: { __varroReceive?: (payload: unknown) => void; __varroReceiveBatch?: (payloads: unknown[]) => void },
  deliver: (payload: unknown) => void,
) {
  target.__varroReceive = deliver;
  target.__varroReceiveBatch = (payloads) => {
    for (const payload of payloads) deliver(payload);
  };
}
