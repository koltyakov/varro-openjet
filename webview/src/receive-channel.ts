/** Keep native batches ordered while preserving the upstream one-message event contract. */
export function installReceiveChannel(
  target: { __varroReceive?: (payload: unknown) => void; __varroReceiveBatch?: (payloads: unknown[]) => void },
  deliver: (payload: unknown) => void,
) {
  target.__varroReceive = deliver;
  target.__varroReceiveBatch = (payloads) => {
    // One failing listener must not drop the rest of the batch, such as a later
    // `api/response` its caller is waiting on.
    for (const payload of payloads) {
      try {
        deliver(payload);
      } catch (error) {
        console.error('Varro host message delivery failed', error);
      }
    }
  };
}
