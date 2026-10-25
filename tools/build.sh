#!/usr/bin/env bash
# Build the plugin without installing Java: runs the Gradle wrapper in a JDK 17 container.
# Builds and tests one jar per VME line from the same source:
#   dist/com-morpheus-plugin-<version>-vme9.0.jar   VME 9.0.x (Plugin API 1.4.1)
#   dist/com-morpheus-plugin-<version>-vme9.1.jar   VME 9.1.x (Plugin API 1.5.0)
#
#   tools/build.sh                     # test + build both
#   VME_TARGETS=9.0 tools/build.sh     # just one line
#   tools/build.sh shadowJar           # other Gradle tasks, for each target
set -euo pipefail
cd "$(dirname "$0")/.."
IMAGE="${BUILD_IMAGE:-eclipse-temurin:17-jdk}"
CACHE="${GRADLE_CACHE_VOLUME:-vme-insight-plugin-gradle}"
TARGETS="${VME_TARGETS:-9.0 9.1}"
tasks=("$@")
[ ${#tasks[@]} -eq 0 ] && tasks=(clean test shadowJar)
# Gradle's cache lives in a named volume; Docker creates it root-owned, so hand it to us first.
docker run --rm -u 0 -v "$CACHE":/cache "$IMAGE" chown "$(id -u):$(id -g)" /cache
mkdir -p dist
for t in $TARGETS; do
  echo "== VME $t"
  docker run --rm \
    -u "$(id -u):$(id -g)" \
    -e GRADLE_USER_HOME=/cache \
    -v "$CACHE":/cache \
    -v "$PWD":/src -w /src \
    "$IMAGE" ./gradlew --no-daemon -q -PvmeTarget="$t" "${tasks[@]}"
  cp build/vme"$t"/libs/*-vme"$t".jar dist/ 2>/dev/null || true
done
ls -l dist/
