#!/usr/bin/env bash
# Install curl, jq, bash for deploy-jenkins when missing (shell executor on Linux).
set -euo pipefail

if command -v curl >/dev/null 2>&1 && command -v jq >/dev/null 2>&1 && command -v bash >/dev/null 2>&1; then
  exit 0
fi

run_privileged() {
  if [[ "$(id -u)" -eq 0 ]]; then
    "$@"
  elif command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null; then
    # Non-interactive only — bare "sudo" fails in CI (no TTY / no password).
    sudo -n "$@"
  else
    echo "ERROR: install curl, jq, bash on this runner (need root or passwordless sudo)." >&2
    if [[ -n "${CI:-}" ]] || [[ -n "${GITLAB_CI:-}" ]] || [[ -n "${GITHUB_ACTIONS:-}" ]]; then
      echo "Hint: install in the job before this script, e.g. Alpine: apk add --no-cache curl jq bash" >&2
      echo "  Debian/Ubuntu: apt-get update -qq && apt-get install -y curl jq bash" >&2
    fi
    exit 1
  fi
}

PKGS=(curl jq bash)

if command -v apk >/dev/null 2>&1; then
  run_privileged apk add --no-cache "${PKGS[@]}"
elif command -v apt-get >/dev/null 2>&1; then
  run_privileged apt-get update -qq
  run_privileged apt-get install -y "${PKGS[@]}"
elif command -v dnf >/dev/null 2>&1; then
  run_privileged dnf install -y "${PKGS[@]}"
elif command -v yum >/dev/null 2>&1; then
  run_privileged yum install -y "${PKGS[@]}"
elif command -v zypper >/dev/null 2>&1; then
  run_privileged zypper install -y "${PKGS[@]}"
else
  echo "ERROR: unsupported package manager; install: ${PKGS[*]}" >&2
  exit 1
fi
