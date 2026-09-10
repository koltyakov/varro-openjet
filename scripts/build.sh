#!/usr/bin/env bash
#
# Builds the Varro OpenJet plugin inside Docker, so the host needs neither a JDK
# nor Node. The installable zip lands in ./dist.
#
#   ./scripts/build.sh              # incremental build against the working tree
#   ./scripts/build.sh clean        # clean build
#   ./scripts/build.sh verify       # Kotlin and webview host tests
#   ./scripts/build.sh shell        # interactive shell in the build container
#   ./scripts/build.sh gradle <...> # arbitrary Gradle invocation
#
set -euo pipefail

cd "$(dirname "$0")/.."

if ! command -v docker >/dev/null 2>&1; then
  echo "error: docker is not installed or not on PATH." >&2
  exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
  echo "error: 'docker compose' is unavailable. Docker Compose v2 is required." >&2
  exit 1
fi

# Docker Desktop remaps bind-mount ownership; native Linux Docker does not.
if [ "$(uname -s)" = "Linux" ]; then
  VARRO_UID="$(id -u)"
  VARRO_GID="$(id -g)"
else
  VARRO_UID=1000
  VARRO_GID=1000
fi
# Rebuild only when inputs to the image's toolchain/dependency layer change.
# Source-only changes stay on the fast bind-mounted incremental path.
BUILD_ENV_INPUTS="$(cksum Dockerfile webview/package.json webview/package-lock.json)"
VARRO_BUILD_ENV_HASH="$(printf '%s\n%s:%s\n' "$BUILD_ENV_INPUTS" "$VARRO_UID" "$VARRO_GID" | cksum | cut -d ' ' -f 1)"
export VARRO_UID VARRO_GID VARRO_BUILD_ENV_HASH

IMAGE_BUILD_ENV_HASH="$(docker image inspect varro-openjet-build \
  --format '{{ index .Config.Labels "io.varro.build-env-hash" }}' 2>/dev/null || true)"
if [ "$IMAGE_BUILD_ENV_HASH" != "$VARRO_BUILD_ENV_HASH" ]; then
  echo "==> Refreshing build image"
  docker compose --progress plain build shell
fi

mkdir -p dist

# The vendored upstream webview is committed, but a shallow or partial clone may
# not have it. Failing here beats failing deep inside Gradle.
if [ ! -d webview/vendor/webview ]; then
  echo "error: webview/vendor is missing." >&2
  echo "       Run: docker compose run --rm shell -lc 'cd webview && npm run sync'" >&2
  exit 1
fi

command="${1:-dev}"
shift || true

case "${command}" in
  dev)
    echo "==> Incremental build (working tree mounted)"
    docker compose run --rm dev
    ;;
  clean)
    echo "==> Clean build (image-internal copy of the sources)"
    docker compose run --rm build
    ;;
  verify)
    echo "==> Kotlin and webview host tests"
    docker compose run --rm verify
    ;;
  shell)
    docker compose run --rm shell "$@"
    ;;
  gradle)
    docker compose run --rm shell -lc "./gradlew --no-daemon $*"
    ;;
  *)
    echo "usage: $0 {dev|clean|verify|shell|gradle <args>}" >&2
    exit 2
    ;;
esac

if [ -d dist ] && compgen -G "dist/*.zip" >/dev/null; then
  echo
  echo "Plugin artifact:"
  ls -lh dist/*.zip
  echo
  echo "Install in the IDE with: Settings | Plugins | gear icon | Install Plugin from Disk…"
fi
