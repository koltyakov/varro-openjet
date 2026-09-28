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
#   ./scripts/build.sh prune        # trim this project's Docker build cache
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

if ! docker buildx version >/dev/null 2>&1; then
  echo "error: Docker Buildx is required for the project's bounded build cache." >&2
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
command="${1:-dev}"
shift || true

case "$command" in
  dev|clean|verify|shell|gradle|prune) ;;
  *) echo "usage: $0 {dev|clean|verify|shell|gradle <args>|prune}" >&2; exit 2 ;;
esac

# An isolated builder lets us trim OpenJet's image layers without pruning other
# projects. Never select it globally: every build/prune names it explicitly.
BUILDER=varro-openjet
BUILDER_DRIVER="$(docker buildx inspect "$BUILDER" --format '{{.Driver}}' 2>/dev/null || true)"
if [ -n "$BUILDER_DRIVER" ] && [ "$BUILDER_DRIVER" != "docker-container" ]; then
  echo "error: Docker builder '$BUILDER' must use the docker-container driver." >&2
  exit 1
fi

cleanup_cache() {
  # Only dangling images carrying this project's label are eligible.
  docker image prune --force --filter label=io.varro.build-env-hash ||
    echo "warning: could not remove obsolete OpenJet images." >&2
  if [ -n "$BUILDER_DRIVER" ]; then
    docker buildx prune --builder "$BUILDER" --force --max-used-space 4GB --reserved-space 1GB --min-free-space 10GB ||
      echo "warning: could not trim the OpenJet build cache." >&2
  fi
}

echo "==> Cleaning obsolete OpenJet build data"
cleanup_cache
if [ "$command" = "prune" ]; then exit 0; fi
# Also clean up after a failed image build, preserving the original exit status.
trap cleanup_cache EXIT

# Bind-mounted builds only need a refreshed image when the toolchain changes.
# Clean builds also refresh the image's source snapshot on every invocation.
BUILD_ENV_INPUTS="$(cksum Dockerfile .dockerignore webview/package.json webview/package-lock.json)"
VARRO_BUILD_ENV_HASH="$(printf '%s\n%s:%s\n' "$BUILD_ENV_INPUTS" "$VARRO_UID" "$VARRO_GID" | cksum | cut -d ' ' -f 1)"
export VARRO_UID VARRO_GID VARRO_BUILD_ENV_HASH

IMAGE_BUILD_ENV_HASH="$(docker image inspect varro-openjet-build \
  --format '{{ index .Config.Labels "io.varro.build-env-hash" }}' 2>/dev/null || true)"
if [ "$command" = "clean" ] || [ "$IMAGE_BUILD_ENV_HASH" != "$VARRO_BUILD_ENV_HASH" ]; then
  if [ -z "$BUILDER_DRIVER" ]; then
    docker buildx create --name "$BUILDER" --driver docker-container \
      --driver-opt default-load=true --buildkitd-config docker/buildkitd.toml
    BUILDER_DRIVER=docker-container
  fi
  echo "==> Refreshing build image"
  docker compose --progress plain build --builder "$BUILDER" shell
fi

mkdir -p dist

# The vendored upstream webview is committed, but a shallow or partial clone may
# not have it. Failing here beats failing deep inside Gradle.
if [ ! -d webview/vendor/webview ]; then
  echo "error: webview/vendor is missing." >&2
  echo "       Run: docker compose run --rm shell bash -lc 'cd webview && npm run sync'" >&2
  exit 1
fi

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
    docker compose run --rm shell bash "$@"
    ;;
  gradle)
    docker compose run --rm shell ./gradlew --no-daemon "$@"
    ;;
esac

if [ -d dist ] && compgen -G "dist/*.zip" >/dev/null; then
  echo
  echo "Plugin artifact:"
  ls -lh dist/*.zip
  echo
  echo "Install in the IDE with: Settings | Plugins | gear icon | Install Plugin from Disk…"
fi
