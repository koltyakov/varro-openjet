#!/usr/bin/env bash
#
# Installs or updates Varro OpenJet into a locally installed JetBrains IDE,
# without going through the Plugins dialog.
#
#   ./scripts/install.sh                  # build, then install into every detected IDE
#   ./scripts/install.sh --no-build       # install the existing dist/ artifact
#   ./scripts/install.sh --ide IntelliJIdea2026.2
#   ./scripts/install.sh --list           # show detected IDE config directories
#   ./scripts/install.sh --all            # include IDEs outside the supported range
#   ./scripts/install.sh --uninstall
#
# A JetBrains IDE reads its plugins directory only at startup, so the IDE has to
# be restarted afterwards. The script says so; it never restarts the IDE for you,
# because that would discard unsaved work.
#
set -euo pipefail

cd "$(dirname "$0")/.."

PLUGIN_DIR_NAME="varro-openjet"
BUILD=1
TARGET_IDE=""
ACTION="install"
INCLUDE_INCOMPATIBLE=0

# Kept in step with the plugin's declared compatibility range, so this script
# cannot offer to install somewhere the IDE would then refuse to load it.
SINCE_BUILD="$(sed -n 's/^pluginSinceBuild=//p' gradle.properties)"
UNTIL_BUILD="$(sed -n 's/^pluginUntilBuild=//p' gradle.properties | sed 's/\..*//')"

while [ $# -gt 0 ]; do
  case "$1" in
    --no-build) BUILD=0 ;;
    --ide) TARGET_IDE="${2:-}"; shift ;;
    --list) ACTION="list" ;;
    --all) INCLUDE_INCOMPATIBLE=1 ;;
    --uninstall) ACTION="uninstall"; BUILD=0 ;;
    -h|--help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "error: unknown option '$1' (try --help)" >&2; exit 2 ;;
  esac
  shift
done

# --- Locate JetBrains configuration directories -------------------------------
#
# The plugins directory lives beside the IDE's settings, not inside the
# application bundle, so it survives IDE updates and needs no elevated rights.
case "$(uname -s)" in
  Darwin) CONFIG_ROOT="$HOME/Library/Application Support/JetBrains" ;;
  Linux)  CONFIG_ROOT="$HOME/.local/share/JetBrains" ;;
  *)      echo "error: unsupported platform $(uname -s). On Windows use %APPDATA%\\JetBrains." >&2; exit 1 ;;
esac

if [ ! -d "$CONFIG_ROOT" ]; then
  echo "error: no JetBrains configuration directory at $CONFIG_ROOT" >&2
  exit 1
fi

# Turns a profile name such as `IntelliJIdea2026.2` into its build number, 262.
# JetBrains build numbers follow (year - 2000) * 10 + release, so 2025.1 is 251
# and 2026.2 is 262. Prints nothing when the name carries no version.
profile_build() {
  echo "$1" | sed -n 's/.*\(20[0-9][0-9]\)\.\([0-9]\).*/\1 \2/p' | while read -r year release; do
    echo $(( (year - 2000) * 10 + release ))
  done
}

is_compatible() {
  build="$(profile_build "$1")"
  [ -z "$build" ] && return 1
  [ "$build" -ge "$SINCE_BUILD" ] && [ "$build" -le "$UNTIL_BUILD" ]
}

detect_ides() {
  # Any directory holding a `plugins` subdirectory is an IDE profile. Toolbox and
  # standalone installs both land here, one directory per product and version.
  find "$CONFIG_ROOT" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | while read -r dir; do
    name="$(basename "$dir")"
    case "$name" in
      Toolbox|consentOptions|crl|discovery|Daemon|bl|Air|acp-agents) continue ;;
    esac
    [ -d "$dir/plugins" ] && echo "$name"
  done | sort
}

# macOS ships bash 3.2, which has no `mapfile`, so the array is filled by hand.
DETECTED=()
while IFS= read -r line; do
  [ -n "$line" ] && DETECTED+=("$line")
done < <(detect_ides)

if [ "${#DETECTED[@]:-0}" -eq 0 ]; then
  echo "error: found no IDE profiles under $CONFIG_ROOT" >&2
  exit 1
fi

if [ "$ACTION" = "list" ]; then
  echo "IDE profiles under $CONFIG_ROOT"
  echo "(plugin supports builds ${SINCE_BUILD}-${UNTIL_BUILD})"
  echo
  for ide in "${DETECTED[@]}"; do
    marker=" "
    [ -d "$CONFIG_ROOT/$ide/plugins/$PLUGIN_DIR_NAME" ] && marker="*"
    note=""
    is_compatible "$ide" || note="   (unsupported build $(profile_build "$ide"))"
    printf '  %s %s%s\n' "$marker" "$ide" "$note"
  done
  echo
  echo "  * = Varro OpenJet currently installed"
  exit 0
fi

if [ -n "$TARGET_IDE" ]; then
  TARGETS=("$TARGET_IDE")
  if [ ! -d "$CONFIG_ROOT/$TARGET_IDE/plugins" ]; then
    echo "error: no such IDE profile '$TARGET_IDE'. Run --list to see the options." >&2
    exit 1
  fi
else
  # Only IDEs the plugin actually supports. Installing into an older one would
  # just make it report an incompatible plugin at startup.
  TARGETS=()
  for ide in "${DETECTED[@]}"; do
    if [ "$INCLUDE_INCOMPATIBLE" -eq 1 ] || is_compatible "$ide"; then
      TARGETS+=("$ide")
    fi
  done
  if [ "${#TARGETS[@]:-0}" -eq 0 ]; then
    echo "error: no IDE in the supported build range ${SINCE_BUILD}-${UNTIL_BUILD}." >&2
    echo "       Run --list to see what was found, or --all to install anyway." >&2
    exit 1
  fi
fi

# --- Uninstall ----------------------------------------------------------------
if [ "$ACTION" = "uninstall" ]; then
  for ide in "${TARGETS[@]}"; do
    target="$CONFIG_ROOT/$ide/plugins/$PLUGIN_DIR_NAME"
    if [ -d "$target" ]; then
      rm -rf "$target"
      echo "Removed from $ide"
    else
      echo "Not installed in $ide"
    fi
  done
  echo
  echo "Restart the IDE to apply."
  exit 0
fi

# --- Build --------------------------------------------------------------------
if [ "$BUILD" -eq 1 ]; then
  echo "==> Building"
  ./scripts/build.sh
fi

ARTIFACT="$(ls -t dist/*.zip 2>/dev/null | head -1 || true)"
if [ -z "$ARTIFACT" ]; then
  echo "error: no artifact in dist/. Run ./scripts/build.sh first, or drop --no-build." >&2
  exit 1
fi
echo "==> Artifact: $ARTIFACT"

# --- Install ------------------------------------------------------------------
for ide in "${TARGETS[@]}"; do
  plugins="$CONFIG_ROOT/$ide/plugins"
  target="$plugins/$PLUGIN_DIR_NAME"

  # Replace rather than overlay: a stale jar left behind from a previous version
  # would still be on the plugin classpath and could win class resolution.
  rm -rf "$target"
  mkdir -p "$plugins"
  unzip -q "$ARTIFACT" -d "$plugins"

  if [ ! -d "$target" ]; then
    echo "error: the archive did not unpack to '$PLUGIN_DIR_NAME' in $ide" >&2
    exit 1
  fi
  echo "==> Installed into $ide"
done

echo
echo "Restart the IDE to load it."
case "$(uname -s)" in
  Darwin) echo "  osascript -e 'quit app \"IntelliJ IDEA\"' && sleep 3 && open -a 'IntelliJ IDEA'" ;;
esac
