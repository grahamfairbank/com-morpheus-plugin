#!/usr/bin/env bash
# Build the plugin without installing Java: runs the Gradle wrapper in a JDK 17 container.
#
#   tools/build.sh             # test + build dist/com-morpheus-plugin-<version>.jar, then compatibility tests
#   COMPAT_APIS="" tools/build.sh   # skip the compatibility test run
#   tools/build.sh <tasks...>  # run other Gradle tasks (no compatibility run)
#
# One jar serves Morpheus 9.0 and 9.1: it is compiled against the oldest supported Plugin API (1.4.1).
# The compatibility run repeats the whole test suite against newer Plugin APIs: by default the latest one
# recorded in compat/plugin-core-baseline.properties (see tools/check-plugin-core.sh).
set -euo pipefail
cd "$(dirname "$0")/.."
IMAGE="${BUILD_IMAGE:-eclipse-temurin:17-jdk}"
CACHE="${GRADLE_CACHE_VOLUME:-com-morpheus-plugin-gradle}"   # own cache: a shared one let parallel builds collide
DEFAULT_COMPAT=$(grep -E '^mavenLatest=' compat/plugin-core-baseline.properties 2>/dev/null | cut -d= -f2 || true)
COMPAT_APIS="${COMPAT_APIS-$DEFAULT_COMPAT}"
tasks=("$@")
custom=${#tasks[@]}
[ "$custom" -eq 0 ] && tasks=(clean test shadowJar)

gradle() {
  docker run --rm -u "$(id -u):$(id -g)" -e GRADLE_USER_HOME=/cache \
    -v "$CACHE":/cache -v "$PWD":/src -w /src "$IMAGE" ./gradlew --no-daemon -q "$@"
}

# Gradle's cache lives in a named volume; Docker creates it root-owned, so hand it to us first.
docker run --rm -u 0 -v "$CACHE":/cache "$IMAGE" chown "$(id -u):$(id -g)" /cache

echo "== build (Plugin API 1.4.1)"
gradle "${tasks[@]}"
if [ "$custom" -eq 0 ]; then
  mkdir -p dist
  cp build/libs/com-morpheus-plugin-*.jar dist/
  for v in $COMPAT_APIS; do
    [ "$v" = "1.4.1" ] && continue
    echo "== compatibility tests against Plugin API $v"
    gradle -PtestApiVersion="$v" clean test
  done
fi
ls -l dist/ 2>/dev/null || true
