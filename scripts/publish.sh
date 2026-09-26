#!/usr/bin/env bash
set -euo pipefail
version=$(python3 - <<'PY'
import re
text = open("build.gradle").read()
match = re.search(r'^version = "([0-9.]+)"', text, re.M)
if not match:
    raise SystemExit("version missing from build.gradle")
print(match.group(1))
PY
)
tag="${GITHUB_REF_NAME:-}"
if [[ -n "$tag" && "$tag" != "v${version}" ]]; then
  echo "tag ${tag} does not match package version ${version}" >&2
  exit 1
fi
if [[ "${DRY_RUN:-0}" == 1 ]]; then
  gradle --no-daemon test installDist
  exit 0
fi
if [[ -z "${ORG_GRADLE_PROJECT_mavenCentralUsername:-}" || -z "${ORG_GRADLE_PROJECT_signingInMemoryKey:-}" ]]; then
  echo "Maven Central credentials and a signing key are required" >&2
  exit 1
fi
gradle --no-daemon publish
