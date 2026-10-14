#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

fail() {
  echo "::error::$*" >&2
  exit 1
}

extract_one() {
  local label="$1" file="$2" pattern="$3"
  local -a matches=()
  mapfile -t matches < <(sed -nE "s|${pattern}|\\1|p" "$file")
  if [[ ${#matches[@]} -ne 1 || -z "${matches[0]}" ]]; then
    fail "Expected exactly one source value for ${label} in ${file}; found ${#matches[@]}."
  fi
  printf '%s' "${matches[0]}"
}

check_exact_line() {
  local file="$1" expected="$2"
  if ! grep -Fqx -- "$expected" "$file"; then
    fail "Missing exact documentation row in ${file}: ${expected}"
  fi
}

check_table_row_value() {
  local file="$1" label="$2" value="$3"
  if ! awk -F '|' -v wanted_label="$label" -v wanted_value="$value" '
    function trim(s) {
      sub(/^[[:space:]]+/, "", s)
      sub(/[[:space:]]+$/, "", s)
      return s
    }
    substr($0, 1, 1) == "|" && trim($2) == wanted_label && trim($3) == wanted_value {
      matches++
    }
    END { exit(matches == 1 ? 0 : 1) }
  ' "$file"; then
    fail "Expected exactly one anchored table row in ${file} for ${label}=${value}."
  fi
}

APP_VERSION="$(extract_one 'versionName' app/build.gradle.kts '^[[:space:]]*versionName = "([^"]+)".*$')"
APP_CODE="$(extract_one 'versionCode' app/build.gradle.kts '^[[:space:]]*versionCode = ([0-9]+).*$')"
WIRE_VERSION="$(extract_one 'Protocol.VERSION' app/src/main/java/com/hamseda/walkie/proto/Protocol.kt '^[[:space:]]*const val VERSION: Byte = ([0-9]+).*$')"
BITRATE_BPS="$(extract_one 'Opus target bitrate' app/src/main/java/com/hamseda/walkie/audio/OpusCodec.kt '^[[:space:]]*const val DEFAULT_BITRATE_BPS = ([0-9_]+).*$')"
KEY_INFO="$(extract_one 'SessionCrypto.INFO' app/src/main/java/com/hamseda/walkie/crypto/SessionCrypto.kt '^[[:space:]]*private const val INFO = "([^"]+)".*$')"
SAS_INFO="$(extract_one 'SessionCrypto.SAS_INFO' app/src/main/java/com/hamseda/walkie/crypto/SessionCrypto.kt '^[[:space:]]*private const val SAS_INFO = "([^"]+)".*$')"
AGP_VERSION="$(extract_one 'Android Gradle Plugin version' gradle/libs.versions.toml '^[[:space:]]*agp = "([^"]+)".*$')"
GRADLE_VERSION="$(extract_one 'Gradle wrapper version' gradle/wrapper/gradle-wrapper.properties '^[[:space:]]*distributionUrl=.*gradle-([^/-]+)-bin\.zip.*$')"

check_exact_line README.md "| نسخهٔ نمایشی برنامه (`versionName`) | `${APP_VERSION}` |"
check_exact_line README.md "| کد نسخه (`versionCode`) | `${APP_CODE}` |"
check_exact_line README.md "| پروتکل ارتباطی سیم (`Protocol.VERSION`) | `${WIRE_VERSION}` |"
check_exact_line README.md "| نرخ هدف Opus (`DEFAULT_BITRATE_BPS`) | `${BITRATE_BPS} bps` |"
check_table_row_value README.md "Android Gradle Plugin" "$AGP_VERSION"
check_table_row_value README.md "Gradle Wrapper" "$GRADLE_VERSION"

check_exact_line SECURITY.md "| `SessionCrypto.INFO` | `${KEY_INFO}` |"
check_exact_line SECURITY.md "| `SessionCrypto.SAS_INFO` | `${SAS_INFO}` |"

echo "Consistency checks passed: versionName=$APP_VERSION, versionCode=$APP_CODE, wireVersion=$WIRE_VERSION, opusBitrateBps=$BITRATE_BPS, AGP=$AGP_VERSION, Gradle=$GRADLE_VERSION."
