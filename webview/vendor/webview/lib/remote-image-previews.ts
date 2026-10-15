import type { RemoteImageResponse } from '../../shared/remote-images';

const previews = new Map<string, { image: RemoteImageResponse; expires: number }>();
const CACHE_TTL_MS = 5 * 60_000;
const CACHE_MAX_BYTES = 4 * 1024 * 1024;

export function clearRemoteImagePreviews() {
  previews.clear();
}

export function getRemoteImagePreview(key: string): RemoteImageResponse | null {
  const entry = previews.get(key);
  if (!entry) return null;
  if (entry.expires <= Date.now()) {
    previews.delete(key);
    return null;
  }
  return entry.image;
}

/** Keep approved thumbnails only. A cache miss never initiates a download. */
export function rememberRemoteImagePreview(key: string, image: RemoteImageResponse) {
  previews.delete(key);
  previews.set(key, { image, expires: Date.now() + CACHE_TTL_MS });
  let bytes = 0;
  for (const value of previews.values()) bytes += value.image.url.length * 2;
  while (previews.size > 64 || bytes > CACHE_MAX_BYTES) {
    const oldest = previews.keys().next().value;
    if (oldest === undefined) break;
    bytes -= previews.get(oldest)!.image.url.length * 2;
    previews.delete(oldest);
  }
}
