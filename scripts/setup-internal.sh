#!/usr/bin/env bash
set -euo pipefail

readonly REPOSITORY_OWNER="kchanis1223"
readonly REPOSITORY_NAME="SubAuth"
readonly REPOSITORY_URL="https://maven.pkg.github.com/${REPOSITORY_OWNER}/${REPOSITORY_NAME}"
readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly SETTINGS_HELPER="${SCRIPT_DIR}/ConfigureMavenSettings.java"

assume_yes=false
check_only=false
skip_provider_checks=false
package_version="${SUBAUTH_VERSION:-}"

usage() {
  cat <<'EOF'
Usage: scripts/setup-internal.sh [options]

Configure this Mac to consume SubAuth from the private GitHub Packages registry.

Options:
  --yes                  Update Maven settings without an interactive prompt.
  --check                Check prerequisites without changing Maven settings.
  --skip-provider-checks Skip Codex, Claude Code, and Antigravity checks.
  --version VERSION      Download and verify a published SubAuth starter version.
  --help                 Show this help.

Authentication:
  Set SUBAUTH_GITHUB_TOKEN to a classic PAT with read:packages, or authenticate
  GitHub CLI and grant it the read:packages scope:

    gh auth refresh -h github.com -s read:packages
EOF
}

while (($# > 0)); do
  case "$1" in
    --yes)
      assume_yes=true
      shift
      ;;
    --check)
      check_only=true
      shift
      ;;
    --skip-provider-checks)
      skip_provider_checks=true
      shift
      ;;
    --version)
      if (($# < 2)); then
        echo "--version requires a value" >&2
        exit 2
      fi
      package_version="$2"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "SubAuth currently supports macOS only." >&2
  exit 1
fi

for command_name in java mvn gh; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "Required command is missing: $command_name" >&2
    exit 1
  fi
done

java_version="$(java -version 2>&1 | awk -F '"' 'NR == 1 { print $2 }')"
java_major="${java_version%%.*}"
if [[ "$java_major" == "1" ]]; then
  java_major="$(printf '%s' "$java_version" | cut -d. -f2)"
fi
if [[ ! "$java_major" =~ ^[0-9]+$ ]] || ((java_major < 21)); then
  echo "Java 21 or newer is required; found ${java_version:-unknown}." >&2
  exit 1
fi

github_token="${SUBAUTH_GITHUB_TOKEN:-${GITHUB_TOKEN:-}}"
github_user="${SUBAUTH_GITHUB_USER:-${GITHUB_ACTOR:-}}"
if [[ -n "$github_token" ]]; then
  if [[ -z "$github_user" ]]; then
    github_user="$(GH_TOKEN="$github_token" gh api user --jq .login)"
  fi
else
  if ! gh auth status -h github.com >/dev/null 2>&1; then
    echo "GitHub CLI is not authenticated. Run: gh auth login" >&2
    exit 1
  fi
  if ! gh auth status -h github.com 2>&1 | grep -q "read:packages"; then
    cat >&2 <<'EOF'
The active GitHub CLI token does not advertise the read:packages scope.
Grant it, then run this setup again:

  gh auth refresh -h github.com -s read:packages

Alternatively, set SUBAUTH_GITHUB_TOKEN to a classic PAT with read:packages.
EOF
    exit 1
  fi
  github_token="$(gh auth token -h github.com)"
  if [[ -z "$github_user" ]]; then
    github_user="$(gh api user --jq .login)"
  fi
fi

echo "[ready] macOS $(sw_vers -productVersion)"
echo "[ready] Java $java_version"
echo "[ready] $(mvn --version | sed -n '1p')"
echo "[ready] GitHub account $github_user"

settings_file="${SUBAUTH_MAVEN_SETTINGS:-${HOME}/.m2/settings.xml}"
if [[ "$check_only" == "false" ]]; then
  if [[ "$assume_yes" == "false" ]]; then
    if [[ ! -t 0 ]]; then
      echo "Run interactively or pass --yes to update $settings_file." >&2
      exit 1
    fi
    printf 'Configure SubAuth GitHub Packages in %s? [y/N] ' "$settings_file"
    read -r answer
    if [[ ! "$answer" =~ ^[Yy]$ ]]; then
      echo "Maven settings were not changed."
      exit 0
    fi
  fi

  SUBAUTH_SETUP_GITHUB_TOKEN="$github_token" \
    java "$SETTINGS_HELPER" "$settings_file" "$github_user" "$REPOSITORY_URL"
fi

ready_providers=0
if [[ "$skip_provider_checks" == "false" ]]; then
  if command -v codex >/dev/null 2>&1 \
      && codex login status 2>&1 | grep -q "Logged in using ChatGPT"; then
    echo "[ready] OpenAI via Codex ($(codex --version 2>/dev/null))"
    ready_providers=$((ready_providers + 1))
  else
    echo "[warn] OpenAI subscription runtime is not ready (install/login with codex)."
  fi

  if command -v claude >/dev/null 2>&1 \
      && claude auth status --json 2>/dev/null | grep -Eq '"loggedIn"[[:space:]]*:[[:space:]]*true'; then
    echo "[ready] Claude via Claude Code ($(claude --version 2>/dev/null))"
    ready_providers=$((ready_providers + 1))
  else
    echo "[warn] Claude subscription runtime is not ready (install/login with claude)."
  fi

  if command -v agy >/dev/null 2>&1 && agy models >/dev/null 2>&1; then
    echo "[ready] Gemini via Antigravity ($(agy --version 2>/dev/null))"
    ready_providers=$((ready_providers + 1))
  else
    echo "[warn] Gemini subscription runtime is not ready (install/login with agy)."
  fi

  if ((ready_providers == 0)); then
    echo "[warn] No provider runtime is currently ready; Maven setup is still usable."
  fi
fi

if [[ -n "$package_version" ]]; then
  if [[ "$check_only" == "true" && ! -f "$settings_file" ]]; then
    echo "Cannot verify package $package_version because $settings_file does not exist." >&2
    exit 1
  fi
  echo "Verifying io.github.kchanis1223:subauth-spring-boot-starter:$package_version ..."
  mvn --batch-mode --no-transfer-progress \
    org.apache.maven.plugins:maven-dependency-plugin:3.10.0:get \
    -Dartifact="io.github.kchanis1223:subauth-spring-boot-starter:${package_version}" \
    -Dtransitive=true
  echo "[ready] SubAuth package $package_version"
else
  echo "Package download was skipped; pass --version after the first internal release."
fi

echo "SubAuth internal setup completed."
