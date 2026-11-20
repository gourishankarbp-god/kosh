#!/usr/bin/env bash
# Phone -> web -> phone round trip through an encrypted folder standing in for Google Drive.
set -euo pipefail
cd "$(dirname "$0")/.."
KOSH_CROSS=write ./gradlew -q testDebugUnitTest --tests 'com.prash.kosh.sync.CrossPlatformTest' --rerun-tasks
(cd web && KOSH_CROSS=web npx vitest run test/cross.test.ts)
KOSH_CROSS=read ./gradlew -q testDebugUnitTest --tests 'com.prash.kosh.sync.CrossPlatformTest' --rerun-tasks
echo "Cross-platform sync OK"
