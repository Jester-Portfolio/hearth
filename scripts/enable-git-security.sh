#!/bin/sh
set -eu

cd "$(dirname "$0")/.."
if ! command -v git >/dev/null 2>&1; then
  echo "git not found" >&2
  exit 1
fi
if ! command -v gitleaks >/dev/null 2>&1; then
  echo "gitleaks not installed; install it before enabling hooks" >&2
  exit 1
fi

# The repository stores hooks as regular files; GitHub does not preserve executable bits.
chmod +x .githooks/pre-commit .githooks/pre-push
git config --local core.hooksPath .githooks

echo "Enabled local Git security hooks."
echo "Testing the repository with gitleaks:"
gitleaks git --redact --exit-code 1
