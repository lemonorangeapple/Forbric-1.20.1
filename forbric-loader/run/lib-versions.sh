#!/usr/bin/env bash
# Read the repository-root VERSIONS.properties -- Forbric's single source of truth for target versions --
# into the environment for the run/ scripts. Source it, then use the FORBRIC_* variables:
#
#   HERE="$(cd "$(dirname "$0")" && pwd)"
#   . "$HERE/lib-versions.sh"
#   MC_VER="${MC_VER:-$FORBRIC_MC_VERSION}"
#
# No run/ script may hardcode a Minecraft, Forge or Fabric version again.

__forbric_run_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
__forbric_versions="$(cd "$__forbric_run_dir/../.." && pwd)/VERSIONS.properties"
if [ ! -f "$__forbric_versions" ]; then
  echo "lib-versions: VERSIONS.properties not found at $__forbric_versions" >&2
  exit 1
fi

__forbric_read() {
  sed -n "s/^forbric\.$1[[:space:]]*=[[:space:]]*//p" "$__forbric_versions" | tr -d '[:space:]'
}

FORBRIC_MC_VERSION="$(__forbric_read minecraft.version)"
FORBRIC_FORGE_VERSION="$(__forbric_read forge.version)"
FORBRIC_FABRIC_LOADER="$(__forbric_read fabric.loader)"
FORBRIC_FABRIC_API="$(__forbric_read fabric.api)"
FORBRIC_RUNTIME_NAMESPACE="$(__forbric_read runtime.namespace)"
FORBRIC_PROFILE_NAME="$(__forbric_read profile.name)"
export FORBRIC_MC_VERSION FORBRIC_FORGE_VERSION FORBRIC_FABRIC_LOADER FORBRIC_FABRIC_API
export FORBRIC_RUNTIME_NAMESPACE FORBRIC_PROFILE_NAME
