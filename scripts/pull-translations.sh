#!/bin/bash

# Script to pull translations from Transifex and clean up empty files/directories

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

# Helper function to rename or merge directories
rename_or_merge() {
    local src="$1"
    local dst="$2"
    local src_name=$(basename "$src")
    local dst_name=$(basename "$dst")
    local merge_errors=0
    
    if [ ! -d "$src" ]; then
        echo "  Warning: Source directory does not exist: $src_name"
        return 1
    fi
    
    if [ ! -d "$dst" ]; then
        echo "  Renaming: $src_name -> $dst_name"
        if mv "$src" "$dst"; then
            return 0
        else
            echo "  Error: Failed to rename $src_name to $dst_name"
            return 1
        fi
    else
        echo "  Merging: $src_name -> $dst_name"
        while IFS= read -r -d '' item; do
            if ! mv "$item" "$dst/" 2>/dev/null; then
                echo "  Warning: Failed to move $(basename "$item") from $src_name"
                merge_errors=$((merge_errors + 1))
            fi
        done < <(find "$src" -mindepth 1 -maxdepth 1 -print0 2>/dev/null)
        
        # Only remove source directory if merge was successful
        if [ "$merge_errors" -eq 0 ]; then
            if rmdir "$src" 2>/dev/null; then
                return 0
            else
                echo "  Warning: Could not remove empty directory: $src_name"
                return 1
            fi
        else
            echo "  Error: Some files could not be merged from $src_name"
            return 1
        fi
    fi
}

# Validate XML file is well-formed before processing
validate_xml() {
    local file="$1"
    # Basic validation: check for opening and closing resources tags
    if ! grep -q '<resources' "$file" 2>/dev/null || ! grep -q '</resources>' "$file" 2>/dev/null; then
        echo "  Warning: $file appears to be malformed XML, skipping normalization"
        return 1
    fi
    return 0
}

echo "Pulling translations from Transifex..."

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

# Run tx pull and check for errors
set +e  # Temporarily disable exit on error to check tx pull status
tx pull -a
TX_EXIT_CODE=$?
set -e  # Re-enable exit on error

if [ "$TX_EXIT_CODE" -ne 0 ]; then
    echo "Error: Transifex pull failed with exit code $TX_EXIT_CODE"
    echo "Please check your Transifex configuration and authentication."
    exit 1
fi

echo ""
echo "Renaming and cleaning up directories..."

RENAMED_COUNT=0
REMOVED_COUNT=0

# Process all values-* directories
while IFS= read -r dir; do
    dir_name=$(basename "$dir")
    dir_path=$(dirname "$dir")
    
    case "$dir_name" in
        # Keep these as-is (direct 1:1 mapping from Transifex or already in correct format)
        values-ar|values-b+es+419|values-ca|values-cs|values-de|values-el|values-es|values-es-rES|values-fr|values-it|values-nl|values-pl|values-pt|values-pt-rBR|values-ru)
            ;;
        # RENAME Transifex locales to Android folder names
        values-es_419)
            echo "  Renaming: $dir_name -> values-b+es+419"
            rm -rf "$dir_path/values-b+es+419" 2>/dev/null
            mv "$dir" "$dir_path/values-b+es+419"
            RENAMED_COUNT=$((RENAMED_COUNT + 1))
            ;;
        values-es_ES)
            echo "  Renaming: $dir_name -> values-es-rES"
            rm -rf "$dir_path/values-es-rES" 2>/dev/null
            mv "$dir" "$dir_path/values-es-rES"
            RENAMED_COUNT=$((RENAMED_COUNT + 1))
            ;;
        values-pt_BR)
            echo "  Renaming: $dir_name -> values-pt-rBR"
            rm -rf "$dir_path/values-pt-rBR" 2>/dev/null
            mv "$dir" "$dir_path/values-pt-rBR"
            RENAMED_COUNT=$((RENAMED_COUNT + 1))
            ;;
        # DELETE 0% or near-0% translation locales
        values-arb|values-pt_PT|values-ja|values-ko|values-no|values-fa|values-ro|values-uk|values-yo)
            echo "  Removing: $dir_name (0% translations)"
            rm -rf "$dir"
            REMOVED_COUNT=$((REMOVED_COUNT + 1))
            ;;
        # Handle any other BCP 47 formats
        values-b+*)
            new_name=$(echo "$dir_name" | sed 's/values-b+\([a-z][a-z]*\)+\([A-Z0-9][A-Z0-9]*\)/values-\1_\2/')
            if [ "$new_name" != "$dir_name" ]; then
                echo "  Renaming: $dir_name -> $new_name"
                mv "$dir" "$dir_path/$new_name"
                RENAMED_COUNT=$((RENAMED_COUNT + 1))
            fi
            ;;
    esac
done < <(find "$RES_DIR" -type d -name "values-*" 2>/dev/null | sort)

echo "  Renamed $RENAMED_COUNT directories"
echo "  Removed $REMOVED_COUNT directories"

echo ""
echo "Normalizing XML formatting..."

# Normalize XML
NORMALIZED_COUNT=0
while IFS= read -r file; do
    # Validate XML before processing
    if ! validate_xml "$file"; then
        continue
    fi

    if awk '
        {
            gsub(/[[:space:]]+$/, "")
            if ($0 ~ /^[[:space:]]*<\/resources>[[:space:]]*$/) {
                print "</resources>"
                saw_resources = 1
                next
            }
            if (saw_resources && $0 == "") next
            if (saw_resources && $0 != "") saw_resources = 0
            print
        }
    ' "$file" > "$file.tmp"; then
        if mv "$file.tmp" "$file" 2>/dev/null; then
            NORMALIZED_COUNT=$((NORMALIZED_COUNT + 1))
        else
            echo "  Warning: Failed to replace $file"
            rm -f "$file.tmp"
        fi
    else
        echo "  Warning: Failed to normalize $file"
        rm -f "$file.tmp"
    fi
done < <(find "$RES_DIR" -type f -path "*/values-*/strings.xml" 2>/dev/null)

echo "  Normalized $NORMALIZED_COUNT files"

echo ""
echo "Cleaning up empty files and directories..."

EMPTY_COUNT=0
DELETED_DIRS=0
declare -a dirs_to_check

# Delete empty files and collect their directories
set +e  # Allow commands to fail for empty file detection
while IFS= read -r file; do
    line_count=$(wc -l < "$file" 2>/dev/null | tr -d ' ' || echo "0")
    string_count=$(grep -c '<string name=' "$file" 2>/dev/null || echo "0")
    
    if [ "$line_count" -le 4 ] || [ "$string_count" -eq 0 ]; then
        echo "  Deleting empty file: $file"
        dirs_to_check+=("$(dirname "$file")")
        rm -f "$file"
        EMPTY_COUNT=$((EMPTY_COUNT + 1))
    fi
done < <(find "$RES_DIR" -type f -path "*/values-*/strings.xml" 2>/dev/null)
set -e

# Remove empty directories
for dir in $(printf '%s\n' "${dirs_to_check[@]}" | sort -u | sort -r); do
    [ -d "$dir" ] || continue
    set +e  # Allow find to fail if directory is already gone
    file_count=$(find "$dir" -type f ! -name '.gitkeep' ! -name '.DS_Store' 2>/dev/null | wc -l | tr -d ' ')
    set -e
    if [ "$file_count" -eq 0 ]; then
        echo "  Deleting empty directory: $dir"
        if rmdir "$dir" 2>/dev/null; then
            DELETED_DIRS=$((DELETED_DIRS + 1))
        fi
    fi
done

echo ""
echo "Complete!"
echo "  Renamed: $RENAMED_COUNT, Removed: $REMOVED_COUNT"
echo "  Normalized: $NORMALIZED_COUNT"
echo "  Deleted files: $EMPTY_COUNT, Deleted dirs: $DELETED_DIRS"
