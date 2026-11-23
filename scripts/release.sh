#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <version> [--push]"
  echo "Example: $0 0.2.0"
  exit 1
fi

VERSION="$1"
PUSH="false"

if [[ "${2:-}" == "--push" ]]; then
  PUSH="true"
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [[ -n "$(git status --porcelain)" ]]; then
  echo "Working tree is not clean. Commit or stash changes before release."
  exit 1
fi

echo "==> Running validation matrix"
mise x -- gradle -p core-kmp allTests
mise x -- gradle -p core-kmp apiCheck
mise x -- gradle :core-kmp-example-android:assembleDebug
(
  cd flutter-plugin
  mise x -- flutter analyze
  mise x -- flutter test
)

echo "==> Bumping flutter-plugin version to ${VERSION}"
sed -i.bak -E "s/^version: .*/version: ${VERSION}/" flutter-plugin/pubspec.yaml
rm -f flutter-plugin/pubspec.yaml.bak

# The version is normally bumped in the release PR; commit only if it was not.
if ! git diff --quiet -- flutter-plugin/pubspec.yaml; then
  git add flutter-plugin/pubspec.yaml
  git commit -m "chore(release): v${VERSION}"
fi
git tag "v${VERSION}"

if [[ "$PUSH" == "true" ]]; then
  echo "==> Pushing commit and tags"
  git push
  git push --tags
else
  echo "==> Release commit and tag created locally"
  echo "Run 'git push && git push --tags' when ready."
fi
