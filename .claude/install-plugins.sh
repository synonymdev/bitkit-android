#!/usr/bin/env bash
set -u -o pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MARKETPLACE_DIR="$REPO_ROOT/.claude/plugins"
MARKETPLACE_NAME="bitkit-android"

if ! command -v claude >/dev/null 2>&1; then
  echo "Error: 'claude' CLI not found on PATH. Install Claude Code first." >&2
  exit 1
fi

if [ ! -f "$MARKETPLACE_DIR/.claude-plugin/marketplace.json" ]; then
  echo "Error: $MARKETPLACE_DIR/.claude-plugin/marketplace.json not found." >&2
  exit 1
fi

if claude plugin marketplace list 2>/dev/null | grep -q "\b${MARKETPLACE_NAME}\b"; then
  echo "Updating marketplace '${MARKETPLACE_NAME}'..."
  claude plugin marketplace update "${MARKETPLACE_NAME}" || \
    echo "  (warning: marketplace update failed; continuing)"
else
  echo "Adding marketplace '${MARKETPLACE_NAME}'..."
  claude plugin marketplace add "${MARKETPLACE_DIR}" --scope user
fi

failures=()
for plugin_dir in "${MARKETPLACE_DIR}"/*/; do
  [ -f "${plugin_dir}.claude-plugin/plugin.json" ] || continue
  plugin_name="$(basename "${plugin_dir}")"
  full="${plugin_name}@${MARKETPLACE_NAME}"

  if claude plugin list 2>/dev/null | grep -q "${full}"; then
    echo "Updating plugin '${full}'..."
    claude plugin update "${full}" || failures+=("${full} (update)")
  else
    echo "Installing plugin '${full}'..."
    claude plugin install "${full}" --scope user || failures+=("${full} (install)")
  fi
done

echo ""
if [ "${#failures[@]}" -gt 0 ]; then
  echo "Completed with failures: ${failures[*]}" >&2
  exit 1
fi
echo "Done. Restart Claude Code if it was running."
