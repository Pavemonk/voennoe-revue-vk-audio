#!/usr/bin/env sh
set -eu
GRADLE_VERSION=8.10.2
GRADLE_BASE="${HOME}/.gradle/custom-wrapper"
GRADLE_DIST="${GRADLE_BASE}/gradle-${GRADLE_VERSION}"
if [ ! -x "${GRADLE_DIST}/bin/gradle" ]; then
  mkdir -p "${GRADLE_BASE}"
  ZIP="${TMPDIR:-/tmp}/gradle-${GRADLE_VERSION}-bin.zip"
  echo "Downloading Gradle ${GRADLE_VERSION} for the first build..."
  curl -fL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o "${ZIP}"
  unzip -q -o "${ZIP}" -d "${GRADLE_BASE}"
fi
exec "${GRADLE_DIST}/bin/gradle" "$@"
