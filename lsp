#!/usr/bin/env bash
exec "$(dirname "$0")/.claude/plugins/blocktank-api/skills/lsp/scripts/lsp.sh" "$@"
