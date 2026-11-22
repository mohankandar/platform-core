#!/usr/bin/env bash
# Builds a released tag of platform-core into a separate local Maven repository, for offline builds and install
# bundles. It checks the tag out in a temporary worktree and never touches ~/.m2.
#
#   scripts/build-release.sh -Tag platform-core-v0.1.0 -Repository /path/to/local-repo
set -euo pipefail

tag=""
repo=""
while [ $# -gt 0 ]; do
  case "$1" in
    -Tag|--tag) tag="${2:-}"; shift 2 ;;
    -Repository|--repository) repo="${2:-}"; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ ! "$tag" =~ ^platform-core-v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "-Tag must be a release tag platform-core-vX.Y.Z" >&2
  exit 2
fi
if [ -z "$repo" ]; then
  echo "-Repository <local maven repo path> is required" >&2
  exit 2
fi
mkdir -p "$repo"
repo="$(cd "$repo" && pwd)"
case "$repo" in
  "$HOME/.m2"|"$HOME/.m2/"*) echo "-Repository must not be ~/.m2" >&2; exit 2 ;;
esac

root="$(git rev-parse --show-toplevel)"
git -C "$root" rev-parse -q --verify "refs/tags/$tag" >/dev/null || git -C "$root" fetch --quiet origin "refs/tags/$tag:refs/tags/$tag"
work="$(mktemp -d)"
trap 'git -C "$root" worktree remove --force "$work/src" >/dev/null 2>&1 || true; rm -rf "$work"' EXIT
git -C "$root" worktree add --quiet --detach "$work/src" "$tag"
(cd "$work/src" && mvn -B install -DskipTests "-Dmaven.repo.local=$repo")
echo "Built $tag into $repo"
