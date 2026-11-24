#!/usr/bin/env sh
# Small self-bootstrapping Gradle launcher. Android Studio may also use its bundled Gradle support.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
URL=$(sed -n 's/^distributionUrl=//p' "$ROOT/gradle/wrapper/gradle-wrapper.properties" | sed 's#\\:#:#g')
VERSION=$(printf '%s' "$URL" | sed -n 's/.*gradle-\([0-9][0-9.]*\)-bin.zip.*/\1/p')
BASE="${GRADLE_USER_HOME:-$HOME/.gradle}/yapit-dists/gradle-$VERSION"
GRADLE="$BASE/gradle-$VERSION/bin/gradle"
if [ ! -x "$GRADLE" ]; then
  mkdir -p "$BASE"
  ZIP="$BASE/gradle-$VERSION-bin.zip"
  if command -v curl >/dev/null 2>&1; then curl -fL "$URL" -o "$ZIP"; else wget -O "$ZIP" "$URL"; fi
  unzip -q -o "$ZIP" -d "$BASE"
fi
exec "$GRADLE" -p "$ROOT" "$@"
