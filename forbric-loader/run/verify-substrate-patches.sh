#!/usr/bin/env bash
# Guard the fabric-loader substrate's REQUIRED local patches. Forbric depends on eight changes in
# ../fabric-loader (the 26.2 DedicatedServer entrypoint scan, the ForbricTransformBridge hook, and six
# more); if they drift or get reverted, boots fail in confusing ways. This script fails fast instead.
#
# Every comparison is against the pinned upstream release (forbric.fabric.loader in the repository-root
# VERSIONS.properties), NOT against the substrate's HEAD -- so it holds whether you left the patches as
# working-tree changes or committed them in your own checkout. Set SUBSTRATE_BASE to override the ref.
#
# The reference copies live in patches/fabric-loader/*.patch. To update them intentionally:
#   git -C ../fabric-loader diff <ref> -- <file> > patches/fabric-loader/<name>.patch
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PROJECT="$(cd "$HERE/.." && pwd)"
SUBSTRATE="$(cd "$PROJECT/../fabric-loader" && pwd)"
PATCHES="$PROJECT/patches/fabric-loader"
BASE="${SUBSTRATE_BASE:-$(sed -n 's/^forbric\.fabric\.loader[[:space:]]*=[[:space:]]*//p' "$PROJECT/../VERSIONS.properties" | tr -d '[:space:]')}"

if [ -z "$BASE" ]; then
  echo "[substrate-patches] forbric.fabric.loader missing from VERSIONS.properties" >&2
  exit 3
fi
if ! git -C "$SUBSTRATE" rev-parse --verify --quiet "$BASE^{commit}" >/dev/null; then
  echo "[substrate-patches] upstream ref '$BASE' not present in $SUBSTRATE" >&2
  echo "[substrate-patches]   run ./bootstrap.sh from the repository root" >&2
  exit 3
fi

declare -a FILES=(
  "src/main/java/net/fabricmc/loader/impl/launch/knot/KnotClassLoader.java|0003-knotclassloader-module-resources.patch"
  "src/main/java/net/fabricmc/loader/impl/launch/knot/KnotClassDelegate.java|0004-knotclassdelegate-package-manifest.patch"
  "minecraft/src/main/java/net/fabricmc/loader/impl/game/minecraft/Hooks.java|0005-hooks-defer-fabric-main.patch"
  "src/main/java/net/fabricmc/loader/impl/discovery/ModDiscoverer.java|0006-moddiscoverer-suppress-wrongloader.patch"
  "src/main/java/net/fabricmc/loader/impl/launch/knot/MixinServiceKnot.java|0008-mixinserviceknot-relax-overwrites.patch"
)

fail=0
for entry in "${FILES[@]}"; do
  file="${entry%%|*}"
  patch="${entry##*|}"
  if [ ! -f "$PATCHES/$patch" ]; then
    echo "[substrate-patches] MISSING reference patch: patches/fabric-loader/$patch" >&2
    fail=1
    continue
  fi
  # --strip-trailing-cr: the reference patches are checked in with CRLF on Windows and git diff emits LF; a byte
  # comparison would call every patch drifted on the platform they were generated on.
  if ! git -C "$SUBSTRATE" diff "$BASE" -- "$file" | diff -q --strip-trailing-cr - "$PATCHES/$patch" >/dev/null 2>&1; then
    echo "[substrate-patches] DRIFT in ../fabric-loader/$file" >&2
    echo "[substrate-patches]   live diff no longer matches patches/fabric-loader/$patch" >&2
    echo "[substrate-patches]   if the substrate was reverted: ./bootstrap.sh (or git -C '$SUBSTRATE' apply '$PATCHES/$patch')" >&2
    echo "[substrate-patches]   if the change is intentional: regenerate the reference patch (see header)" >&2
    fail=1
  fi
done

if [ "$fail" -ne 0 ]; then
  exit 3
fi
echo "[substrate-patches] OK - all ${#FILES[@]} fabric-loader patches match the committed references (base $BASE)"
