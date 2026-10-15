#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

app_version="$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' app/build.gradle.kts | head -n1)"
app_code="$(sed -nE 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' app/build.gradle.kts | head -n1)"
wire_version="$(sed -nE 's/^[[:space:]]*const val VERSION: Byte = ([0-9]+).*/\1/p' app/src/main/java/com/hamseda/walkie/proto/Protocol.kt | head -n1)"
key_info="$(sed -nE 's/^[[:space:]]*private const val INFO = "([^"]+)".*/\1/p' app/src/main/java/com/hamseda/walkie/crypto/SessionCrypto.kt | head -n1)"
sas_info="$(sed -nE 's/^[[:space:]]*private const val SAS_INFO = "([^"]+)".*/\1/p' app/src/main/java/com/hamseda/walkie/crypto/SessionCrypto.kt | head -n1)"
gradle_version="$(sed -nE 's/^distributionUrl=.*gradle-([0-9.]+)-bin.zip/\1/p' gradle/wrapper/gradle-wrapper.properties | head -n1)"

require_value() {
  local name="$1"
  local value="$2"
  if [[ -z "$value" ]]; then
    echo "::error::Failed to read $name from the source-of-truth files."
    exit 1
  fi
}

require_value "versionName" "$app_version"
require_value "versionCode" "$app_code"
require_value "Protocol.VERSION" "$wire_version"
require_value "SessionCrypto.INFO" "$key_info"
require_value "SessionCrypto.SAS_INFO" "$sas_info"
require_value "Gradle wrapper" "$gradle_version"

# README must contain exact version rows. These checks fail when the values drift.
if ! grep -EqF "| نسخهٔ نمایشی برنامه (\`versionName\`) | \`$app_version\` |" README.md; then
  echo "::error::README.md is missing the exact versionName row for $app_version"
  exit 1
fi
if ! grep -EqF "| کد نسخه (\`versionCode\`) | \`$app_code\` |" README.md; then
  echo "::error::README.md is missing the exact versionCode row for $app_code"
  exit 1
fi
if ! grep -EqF "| پروتکل ارتباطی سیم (\`Protocol.VERSION\`) | \`$wire_version\` |" README.md; then
  echo "::error::README.md is missing the exact Protocol.VERSION row for $wire_version"
  exit 1
fi

# SECURITY.md must document the exact crypto domains currently in use.
if ! grep -EqF "$key_info" SECURITY.md; then
  echo "::error::SECURITY.md is missing the active session key domain: $key_info"
  exit 1
fi
if ! grep -EqF "$sas_info" SECURITY.md; then
  echo "::error::SECURITY.md is missing the active SAS domain: $sas_info"
  exit 1
fi

# Reject stale domain/version references that previously caused false confidence.
if grep -Eq 'HamSeda-v2/(session-keys|sas)|8\.12\.1|8\.14\.6' SECURITY.md; then
  echo "::error::SECURITY.md contains stale protocol-domain or Gradle metadata."
  exit 1
fi

# Confirm the expected runtime documentation in the repo is still consistent.
if ! grep -EqF "48 kHz" README.md; then
  echo "::error::README.md does not contain the expected 48 kHz audio note."
  exit 1
fi
if ! grep -EqF "32 kbps" README.md; then
  echo "::error::README.md does not contain the expected 32 kbps Opus note."
  exit 1
fi

# Ensure the checked-in Gradle wrapper matches the documented version.
if ! grep -EqF "Gradle Wrapper | \`$gradle_version\`" README.md; then
  echo "::error::README.md is missing the exact Gradle wrapper version row for $gradle_version"
  exit 1
fi

# This script is intentionally exact: a harmless README or doc edit that changes the
# version lines causes a failure rather than silently passing.

echo "Consistency checks passed: versionName=$app_version versionCode=$app_code Protocol.VERSION=$wire_version Gradle=$gradle_version"
