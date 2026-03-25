#!/usr/bin/env bash
set -euo pipefail

# Automated LN invoice creation + payment via Blocktank LSP
#
# Prerequisites:
#   - Dev debug build installed on device/emulator with wallet set up and LDK node running
#   - ADB connected to the device
#   - An open Lightning channel with sufficient inbound capacity
#
# Usage:
#   pay-invoices.sh
#   INVOICE_COUNT=10 pay-invoices.sh
#
# Each invoice amount equals its index (1 sat, 2 sats, ... N sats).
# Invoices are created via the DevToolsProvider ContentProvider (dev builds only).
#
# Environment:
#   APP_ID          App package (default: to.bitkit.dev)
#   INVOICE_COUNT   Number of invoices to create and pay (default: 21)
#   DESCRIPTION     Invoice description. Use {i} as index placeholder (default: dev-payment-{i})
#   MINE_TOTAL      Total blocks to mine after payments (default: 150)
#   MINE_BATCH      Blocks per mining call (default: 10)
#   PAY_DELAY       Seconds between payments (default: 3)

APP_ID="${APP_ID:-to.bitkit.dev}"
INVOICE_COUNT="${INVOICE_COUNT:-21}"
DESCRIPTION="${DESCRIPTION:-dev-payment-{i\}}"
MINE_TOTAL="${MINE_TOTAL:-150}"
MINE_BATCH="${MINE_BATCH:-10}"
PAY_DELAY="${PAY_DELAY:-3}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LSP="$SCRIPT_DIR/lsp.sh"
AUTHORITY="$APP_ID.devtools"

create_invoice() {
    local amount="$1"
    local index="$2"
    local desc="${DESCRIPTION//\{i\}/$index}"
    # Escape characters that could cause shell injection when passed through adb shell
    desc="${desc//\\/\\\\}"
    desc="${desc//\$/\\$}"
    desc="${desc//\`/\\\`}"
    desc="${desc//\"/\\\"}"
    desc="${desc//\'/\'\\\'\'}"
    local raw
    raw=$(adb shell "content call --uri content://$AUTHORITY \
        --method createInvoice \
        --arg '{\"amount\":$amount,\"description\":\"$desc\"}'")

    # Extract JSON from Bundle output: Result: Bundle[{result={"bolt11":"..."}}]
    local json
    json=$(echo "$raw" | sed -n 's/.*result=\({.*}\).*/\1/p')

    if [ -z "$json" ]; then
        echo "ERROR: Failed to parse result from: $raw" >&2
        return 1
    fi

    local bolt11
    bolt11=$(echo "$json" | sed -n 's/.*"bolt11":"\([^"]*\)".*/\1/p')

    if [ -z "$bolt11" ]; then
        local error
        error=$(echo "$json" | sed -n 's/.*"message":"\([^"]*\)".*/\1/p')
        echo "ERROR: ${error:-$json}" >&2
        return 1
    fi

    echo "$bolt11"
}

# Phase 1: Create and pay invoices
echo "=== Creating and paying $INVOICE_COUNT invoices (1..$INVOICE_COUNT sats) ==="
for i in $(seq 1 "$INVOICE_COUNT"); do
    echo ""
    echo "--- Invoice $i/$INVOICE_COUNT ($i sats) ---"

    echo "  Creating invoice..."
    invoice=$(create_invoice "$i" "$i") || exit 1
    echo "  Invoice: ${invoice:0:30}..."

    echo "  Paying via LSP..."
    "$LSP" POST /regtest/channel/pay "{\"invoice\":\"$invoice\"}" > /dev/null
    echo "  Paid."

    sleep "$PAY_DELAY"
done

echo ""
echo "=== $INVOICE_COUNT invoices paid ==="

# Phase 2: Mine blocks
batches=$((MINE_TOTAL / MINE_BATCH))
remainder=$((MINE_TOTAL % MINE_BATCH))
if [ "$batches" -gt 0 ]; then
    echo ""
    echo "=== Mining $MINE_TOTAL blocks in $batches batches of $MINE_BATCH ==="
    for i in $(seq 1 "$batches"); do
        echo "  Batch $i/$batches ($MINE_BATCH blocks)..."
        "$LSP" POST /regtest/chain/mine "{\"count\":$MINE_BATCH}" > /dev/null
        sleep 1
    done
fi
if [ "$remainder" -gt 0 ]; then
    echo "  Remainder ($remainder blocks)..."
    "$LSP" POST /regtest/chain/mine "{\"count\":$remainder}" > /dev/null
fi

echo ""
echo "=== Done: $INVOICE_COUNT invoices paid, $MINE_TOTAL blocks mined ==="
