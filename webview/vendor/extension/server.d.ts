// JetBrains transport contract used by the vendored quota service's type-only
// import. The actual requests travel over the helper's stdin/stdout channel.
export interface OpenCodeServer {
  request(method: string, path: string, body?: unknown, options?: { directory?: string }): Promise<unknown>;
}
