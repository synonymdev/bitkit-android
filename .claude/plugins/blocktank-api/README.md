# Blocktank API Plugin

A Claude Code plugin that gives Claude knowledge of the full Blocktank LSP API, enabling it to autonomously create channels, fund them, mine blocks, pay invoices, and close channels during Blocktank LSP testing.

## Setup

Run once from the repo root (re-run anytime to update):

```sh
./.claude/install-plugins.sh
```

Restart Claude Code once. The `blocktank-api:lsp` skill (invoked as `/lsp`) is then available.

## Usage

Once installed, the skill auto-triggers when you mention things like:
- "mine blocks", "deposit sats", "pay invoice", "force close"
- "channel order", "CJIT", "blocktank", "LSP"

Claude will use the `./lsp` wrapper at the repo root to make API calls directly.

## Configuration

The default API base URL is `https://api.stag0.blocktank.to/blocktank/api/v2` (staging).

To override (e.g., for a local instance):

```bash
export BLOCKTANK_API_URL=http://localhost:9000/api
```

## Cross-project reuse

To use this plugin from another repo (e.g. bitkit-ios), symlink it into that project's `.claude/plugins/`:

```bash
ln -s /path/to/bitkit-android/.claude/plugins/blocktank-api /path/to/other-repo/.claude/plugins/blocktank-api
```
