#!/usr/bin/env bash
# Upload the CI artifact without rebuilding it. Never retry an uncertain upload automatically.
set -euo pipefail

if [[ ! "${GITHUB_REF:-}" =~ ^refs/tags/v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo '::error::Marketplace publishing requires a stable tag such as v0.9.3.' >&2
  exit 1
fi

if [[ -z "${JETBRAINS_MARKETPLACE_TOKEN:-}" ]]; then
  echo '::error::Add the JETBRAINS_MARKETPLACE_TOKEN repository Actions secret before publishing.' >&2
  exit 1
fi

version="${GITHUB_REF#refs/tags/v}"
archive="dist/varro-openjet-${version}.zip"
shopt -s nullglob
archives=(dist/*.zip)
if [[ ${#archives[@]} -ne 1 || ! -f "$archive" ]]; then
  echo "::error::Expected exactly one tested archive: $archive" >&2
  exit 1
fi

# An omitted channel selects Stable. Keep the token out of command-line arguments.
# No --retry: a timed-out POST may already have created the Marketplace update.
printf 'Authorization: Bearer %s\n' "$JETBRAINS_MARKETPLACE_TOKEN" |
  curl --fail-with-body --silent --show-error \
    --connect-timeout 30 --max-time 300 \
    --header @- \
    --form pluginId=34204 \
    --form "file=@${archive}" \
    https://plugins.jetbrains.com/api/updates/upload

echo
echo "Uploaded ${version} to JetBrains Marketplace. Approval is still subject to Marketplace review."
