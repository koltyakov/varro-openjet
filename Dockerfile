# Builds the Varro OpenJet plugin distribution (.zip) inside a container, so no
# JDK, Gradle or Node installation is needed on the host.
#
# The usual entry point is `./scripts/build.sh`, which wires up the cache volumes
# for you. To drive it directly:
#
#   docker build -t varro-openjet-build .
#   docker run --rm -v "$PWD/dist:/out" varro-openjet-build
#
# Gradle has to download the IntelliJ Platform (>1 GB) on a cold build. Mount a
# named volume at /home/builder/.gradle to keep it across runs - docker-compose.yml
# and scripts/build.sh already do.

FROM eclipse-temurin:26-jdk-jammy AS build

ENV DEBIAN_FRONTEND=noninteractive \
    GRADLE_OPTS="--enable-native-access=ALL-UNNAMED -Dorg.gradle.daemon=false -Dorg.gradle.welcome=never" \
    GRADLE_USER_HOME=/home/builder/.gradle \
    NPM_CONFIG_CACHE=/home/builder/.npm

# git for the upstream vendoring script; zip/unzip for plugin packaging.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
        curl ca-certificates gnupg git zip unzip xz-utils \
 && rm -rf /var/lib/apt/lists/*

# Node and npm are pinned to the exact versions in webview/package.json's `volta`
# field. The NodeSource `setup_22.x` script floats to the newest 22.x, so the
# container and a Volta-pinned host would drift apart and rewrite
# package-lock.json against each other on every build.
ARG NODE_VERSION=24.21.0
ARG NPM_VERSION=10.9.9
ARG BUILD_ENV_HASH=unknown
RUN set -eux; \
    case "$(dpkg --print-architecture)" in \
      amd64) NODE_ARCH=x64 ;; \
      arm64) NODE_ARCH=arm64 ;; \
      *) echo "unsupported architecture" >&2; exit 1 ;; \
    esac; \
    curl -fsSL "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-${NODE_ARCH}.tar.xz" -o /tmp/node.tar.xz; \
    tar -xJf /tmp/node.tar.xz -C /usr/local --strip-components=1 --no-same-owner; \
    rm /tmp/node.tar.xz; \
    npm install -g "npm@${NPM_VERSION}"; \
    test "$(node --version)" = "v${NODE_VERSION}"; \
    test "$(npm --version)" = "${NPM_VERSION}"; \
    java -version
LABEL io.varro.build-env-hash="${BUILD_ENV_HASH}"

# Build as a non-root user so artifacts written to a bind mount are owned by the
# invoking user rather than root. The uid/gid are overridable so the mounted
# caches stay writable on Linux hosts, where bind-mount ownership is not remapped.
ARG BUILDER_UID=1000
ARG BUILDER_GID=1000
RUN groupadd --gid "${BUILDER_GID}" builder 2>/dev/null || true \
 && useradd --uid "${BUILDER_UID}" --gid "${BUILDER_GID}" --create-home --shell /bin/bash builder 2>/dev/null || true \
 && mkdir -p /workspace /out "${GRADLE_USER_HOME}" "${NPM_CONFIG_CACHE}" \
 && chown -R "${BUILDER_UID}:${BUILDER_GID}" /workspace /out /home/builder

USER builder
WORKDIR /workspace

# Install npm dependencies first so they cache independently of source changes.
# `npm ci` installs exactly the committed lockfile and fails if it has drifted
# from package.json, which is the behaviour that keeps builds reproducible.
COPY --chown=builder:builder webview/package.json webview/package-lock.json webview/
RUN cd webview && npm ci --no-audit --no-fund

# Then the rest of the sources. .dockerignore keeps host build output, caches and
# the tmp/ reference checkouts out, so the image build stays reproducible.
COPY --chown=builder:builder . .

RUN chmod +x gradlew

# `buildPlugin` transitively runs `buildWebview`, which needs webview/vendor to be
# populated. The repository vendors it, but a fresh clone that skipped the sync
# would otherwise fail deep inside Gradle with a confusing message.
RUN test -d webview/vendor/webview \
 || (echo "ERROR: webview/vendor is missing. Run 'cd webview && npm run sync' before building." && exit 1)

RUN ./gradlew --no-daemon clean buildPlugin

# Copy the artifact to a bind-mounted /out by default.
CMD ["bash", "-lc", "mkdir -p /out && cp build/distributions/*.zip /out/ && ls -lh /out/"]
