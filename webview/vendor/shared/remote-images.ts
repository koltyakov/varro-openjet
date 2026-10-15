export type RemoteImageMode = 'off' | 'ask' | 'on';

// oxlint-disable-next-line anti-slop/no-unknown-parameters -- This boundary parser admits only the explicit application setting values.
export function parseRemoteImageMode(value: unknown): RemoteImageMode {
  return value === 'off' || value === 'on' ? value : 'ask';
}

/** Only HTTPS on the standard port is admitted. Address checks belong to the host. */
export function parseRemoteImageUrl(value: string): URL | null {
  if (value.length > 4096) return null;
  try {
    const url = new URL(value);
    if (url.protocol !== 'https:' || url.username || url.password || url.port) return null;
    url.hash = '';
    if (url.href.length > 4096 || url.hostname.length > 253) return null;
    return url;
  } catch {
    return null;
  }
}

export type RemoteImageResponse = { url: string; hostname: string };
