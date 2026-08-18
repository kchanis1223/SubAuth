#!/usr/bin/env bash

set -euo pipefail

version="${1:-0.2.0-SNAPSHOT}"
maven_repo="${2:-${HOME}/.m2/repository}"
group_path="io/github/kchanis1223"
modules=(
  subauth-parent
  subauth-spring-ai
  subauth-spring-boot-autoconfigure
  subauth-spring-boot-starter
)

for module in "${modules[@]}"; do
  pom="${maven_repo}/${group_path}/${module}/${version}/${module}-${version}.pom"
  if [[ ! -f "${pom}" ]]; then
    echo "Missing installed consumer POM: ${pom}" >&2
    exit 1
  fi

  if grep -Eq \
    '<artifactId>(spring-boot-dependencies|spring-ai-bom)</artifactId>|<scope>import</scope>' \
    "${pom}"; then
    echo "Consumer POM must not import framework BOMs: ${pom}" >&2
    exit 1
  fi
done

echo "Verified SubAuth ${version} consumer POMs: no framework BOM imports."
