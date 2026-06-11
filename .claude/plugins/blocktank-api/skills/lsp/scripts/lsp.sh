#!/usr/bin/env bash
set -euo pipefail

# Blocktank LSP API caller
# Usage: ./lsp <GET|POST> <path> [json_body]
#
# Examples:
#   ./lsp GET /info
#   ./lsp GET /channels/abc-123
#   ./lsp POST /channels '{"lspBalanceSat":100000,"channelExpiryWeeks":12}'
#   ./lsp POST /regtest/chain/mine '{"count":6}'
#   ./lsp POST /regtest/chain/deposit '{"address":"bcrt1q...","amountSat":500000}'
#
# Environment:
#   BLOCKTANK_API_URL  Override base URL (default: staging)

BASE_URL="${BLOCKTANK_API_URL:-https://api.stag0.blocktank.to/blocktank/api/v2}"
METHOD="${1:?Usage: ./lsp <GET|POST> <path> [json_body]}"
API_PATH="${2:?Usage: ./lsp <GET|POST> <path> [json_body]}"
BODY="${3:-}"

URL="${BASE_URL}${API_PATH}"

tmpfile=$(mktemp)
trap 'rm -f "$tmpfile"' EXIT

call_api() {
    local http_code
    local response

    if [ "$METHOD" = "GET" ]; then
        http_code=$(curl -s -o "$tmpfile" -w "%{http_code}" "$URL")
    elif [ "$METHOD" = "POST" ]; then
        if [ -n "$BODY" ]; then
            http_code=$(curl -s -o "$tmpfile" -w "%{http_code}" -X POST "$URL" \
                -H "Content-Type: application/json" \
                -d "$BODY")
        else
            http_code=$(curl -s -o "$tmpfile" -w "%{http_code}" -X POST "$URL" \
                -H "Content-Type: application/json" \
                -d '{}')
        fi
    else
        echo "Error: Method must be GET or POST, got '$METHOD'" >&2
        exit 1
    fi

    response=$(cat "$tmpfile")

    if [ "$http_code" -ge 400 ]; then
        echo "HTTP $http_code $METHOD $API_PATH" >&2
        echo "$response"
        exit 1
    fi

    echo "$response"
}

call_api
