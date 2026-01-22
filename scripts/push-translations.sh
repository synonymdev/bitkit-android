#!/bin/bash

# Script to push translations to Transifex
# Handles directory naming conversion between Android format and Transifex format

set -e

# Validate script is run from project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RES_DIR="$PROJECT_ROOT/app/src/main/res"

if [ ! -d "$RES_DIR" ]; then
    echo "Error: Resource directory not found: $RES_DIR"
    echo "Please run this script from the project root directory."
    exit 1
fi

if [ ! -f "$PROJECT_ROOT/.tx/config" ]; then
    echo "Error: Transifex config not found: $PROJECT_ROOT/.tx/config"
    echo "Please ensure Transifex is configured for this project."
    exit 1
fi

# Check if tx command is available
if ! command -v tx &> /dev/null; then
    echo "Error: Transifex CLI (tx) is not installed or not in PATH"
    echo "Please install it: https://developers.transifex.com/docs/cli"
    exit 1
fi

# Check if TX_TOKEN is set
if [ -z "$TX_TOKEN" ]; then
    echo "Error: TX_TOKEN environment variable is not set"
    echo "Please set it with your Transifex API token: export TX_TOKEN=\"your-token\""
    exit 1
fi

# Track renamed directories for cleanup
RENAMED_ES_ES=false
RENAMED_PT_BR=false
RENAMED_ES_419=false

# Cleanup function to restore Android directory names
cleanup() {
    echo ""
    echo "Restoring Android directory names..."

    if [ "$RENAMED_ES_ES" = true ] && [ -d "$RES_DIR/values-es_ES" ]; then
        echo "  Restoring: values-es_ES -> values-es-rES"
        mv "$RES_DIR/values-es_ES" "$RES_DIR/values-es-rES"
    fi

    if [ "$RENAMED_PT_BR" = true ] && [ -d "$RES_DIR/values-pt_BR" ]; then
        echo "  Restoring: values-pt_BR -> values-pt-rBR"
        mv "$RES_DIR/values-pt_BR" "$RES_DIR/values-pt-rBR"
    fi

    if [ "$RENAMED_ES_419" = true ] && [ -d "$RES_DIR/values-es_419" ]; then
        echo "  Restoring: values-es_419 -> values-b+es+419"
        mv "$RES_DIR/values-es_419" "$RES_DIR/values-b+es+419"
    fi

    echo "Cleanup complete."
}

# Set trap to ensure cleanup runs even if script fails
trap cleanup EXIT

echo "Pushing translations to Transifex..."
echo ""

# Step 1: Rename Android directories to Transifex format
echo "Converting directory names to Transifex format..."

if [ -d "$RES_DIR/values-es-rES" ]; then
    echo "  Renaming: values-es-rES -> values-es_ES"
    mv "$RES_DIR/values-es-rES" "$RES_DIR/values-es_ES"
    RENAMED_ES_ES=true
fi

if [ -d "$RES_DIR/values-pt-rBR" ]; then
    echo "  Renaming: values-pt-rBR -> values-pt_BR"
    mv "$RES_DIR/values-pt-rBR" "$RES_DIR/values-pt_BR"
    RENAMED_PT_BR=true
fi

if [ -d "$RES_DIR/values-b+es+419" ]; then
    echo "  Renaming: values-b+es+419 -> values-es_419"
    mv "$RES_DIR/values-b+es+419" "$RES_DIR/values-es_419"
    RENAMED_ES_419=true
fi

echo ""
echo "Running tx push..."

# Step 2: Push source and translations to Transifex
cd "$PROJECT_ROOT"
tx push --source --translation --all --skip --force

echo ""
echo "Push complete!"
