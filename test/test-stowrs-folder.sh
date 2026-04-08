#!/bin/bash

# =============================================================================
# STOW-RS Folder Upload Test Script
# =============================================================================
# Scans a folder for DICOM files and sends them to a STOW-RS endpoint.
# Supports both project-scoped and site-wide endpoints.
# =============================================================================

set -euo pipefail

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

# Defaults
BATCH_SIZE=20
RECURSIVE=true
STRATEGY=""
SITE_WIDE=false
VERBOSE=false
DRY_RUN=false

usage() {
    cat <<'USAGE'
Usage: test-stowrs-folder.sh [OPTIONS] -u URL -d DICOM_DIR

Scans a folder of DICOM files and sends them to a DICOM STOW-RS endpoint.

Required:
  -u URL          Base URL of the XNAT instance (e.g. http://localhost:8080)
  -d DICOM_DIR    Path to directory containing DICOM files

Authentication (one required):
  -a USER:PASS    Basic auth credentials (user:password)
  -t TOKEN        Bearer token for authentication

Endpoint:
  -p PROJECT      Project ID for project-scoped upload
                  (omit for site-wide endpoint)
  -s STRATEGY     Import strategy: GradualDicomImporter or DirectArchive
                  (omit to use server default)

Options:
  -b BATCH_SIZE   Number of DICOM files per request (default: 50)
  -r              Recurse into subdirectories
  -n              Dry run - find and count files without uploading
  -v              Verbose output (show curl details and response bodies)
  -h              Show this help message

Examples:
  # Upload a folder to a project with basic auth
  ./test-stowrs-folder.sh -u http://localhost:8080 -a admin:admin \
      -p MyProject -d /path/to/dicoms

  # Site-wide upload (project resolved from DICOM metadata)
  ./test-stowrs-folder.sh -u http://localhost:8080 -a admin:admin \
      -d /path/to/dicoms

  # Recursive scan with DirectArchive strategy, 20 files per batch
  ./test-stowrs-folder.sh -u http://localhost:8080 -a admin:admin \
      -p MyProject -s DirectArchive -b 20 -r -d /path/to/dicoms

  # Dry run to see what would be uploaded
  ./test-stowrs-folder.sh -u http://localhost:8080 -a admin:admin \
      -p MyProject -n -r -d /path/to/dicoms
USAGE
    exit "${1:-0}"
}

# =============================================================================
# Parse Arguments
# =============================================================================

BASE_URL=""
DICOM_DIR=""
AUTH_HEADER=""
PROJECT=""

while getopts "u:d:a:t:p:s:b:rnvh" opt; do
    case $opt in
        u) BASE_URL="$OPTARG" ;;
        d) DICOM_DIR="$OPTARG" ;;
        a) AUTH_HEADER="Authorization: Basic $(echo -n "$OPTARG" | base64)" ;;
        t) AUTH_HEADER="Authorization: Bearer $OPTARG" ;;
        p) PROJECT="$OPTARG" ;;
        s) STRATEGY="$OPTARG" ;;
        b) BATCH_SIZE="$OPTARG" ;;
        r) RECURSIVE=true ;;
        n) DRY_RUN=true ;;
        v) VERBOSE=true ;;
        h) usage 0 ;;
        *) usage 1 ;;
    esac
done

# Validate required arguments
if [ -z "$BASE_URL" ]; then
    echo -e "${RED}Error: Base URL (-u) is required${NC}" >&2
    echo "" >&2
    usage 1
fi

if [ -z "$DICOM_DIR" ]; then
    echo -e "${RED}Error: DICOM directory (-d) is required${NC}" >&2
    echo "" >&2
    usage 1
fi

if [ -z "$AUTH_HEADER" ]; then
    echo -e "${RED}Error: Authentication required (-a or -t)${NC}" >&2
    echo "" >&2
    usage 1
fi

if [ ! -d "$DICOM_DIR" ]; then
    echo -e "${RED}Error: Directory not found: $DICOM_DIR${NC}" >&2
    exit 1
fi

# Build endpoint URL
if [ -n "$PROJECT" ]; then
    ENDPOINT_URL="${BASE_URL}/xapi/dicomweb/projects/${PROJECT}/studies"
else
    ENDPOINT_URL="${BASE_URL}/xapi/dicomweb/studies"
    SITE_WIDE=true
fi

if [ -n "$STRATEGY" ]; then
    ENDPOINT_URL="${ENDPOINT_URL}?strategy=${STRATEGY}"
fi

# =============================================================================
# Find DICOM Files
# =============================================================================

echo -e "${CYAN}=== STOW-RS Folder Upload ===${NC}"
echo ""
echo "Endpoint:   $ENDPOINT_URL"
echo "Directory:  $DICOM_DIR"
echo "Recursive:  $RECURSIVE"
echo "Batch size: $BATCH_SIZE"
if $SITE_WIDE; then
    echo "Mode:       Site-wide (project resolved from DICOM metadata)"
else
    echo "Project:    $PROJECT"
fi
if [ -n "$STRATEGY" ]; then
    echo "Strategy:   $STRATEGY"
fi
echo ""

echo "Scanning for DICOM files..."

# Build find command based on recursion setting
DICOM_FILES=()
if $RECURSIVE; then
    while IFS= read -r -d '' file; do
        DICOM_FILES+=("$file")
    done < <(find "$DICOM_DIR" -type f \( -name "*.dcm" -o -name "*.DCM" -o -name "*.dicom" -o -name "*.DICOM" \) -print0 2>/dev/null)

    # Also check for extensionless DICOM files by looking for the DICM magic bytes
    while IFS= read -r -d '' file; do
        # Skip files already matched by extension
        case "$file" in
            *.dcm|*.DCM|*.dicom|*.DICOM) continue ;;
        esac
        # Check for DICM marker at offset 128
        if [ "$(dd if="$file" bs=1 skip=128 count=4 2>/dev/null)" = "DICM" ]; then
            DICOM_FILES+=("$file")
        fi
    done < <(find "$DICOM_DIR" -type f -print0 2>/dev/null)
else
    for file in "$DICOM_DIR"/*.dcm "$DICOM_DIR"/*.DCM "$DICOM_DIR"/*.dicom "$DICOM_DIR"/*.DICOM; do
        [ -f "$file" ] && DICOM_FILES+=("$file")
    done

    # Check extensionless files in the directory
    for file in "$DICOM_DIR"/*; do
        [ -f "$file" ] || continue
        case "$file" in
            *.dcm|*.DCM|*.dicom|*.DICOM) continue ;;
        esac
        if [ "$(dd if="$file" bs=1 skip=128 count=4 2>/dev/null)" = "DICM" ]; then
            DICOM_FILES+=("$file")
        fi
    done
fi

FILE_COUNT=${#DICOM_FILES[@]}

if [ "$FILE_COUNT" -eq 0 ]; then
    echo -e "${RED}No DICOM files found in $DICOM_DIR${NC}"
    exit 1
fi

echo -e "${GREEN}Found $FILE_COUNT DICOM file(s)${NC}"
echo ""

if $DRY_RUN; then
    echo -e "${YELLOW}=== DRY RUN - No files will be uploaded ===${NC}"
    echo ""
    BATCH_COUNT=$(( (FILE_COUNT + BATCH_SIZE - 1) / BATCH_SIZE ))
    echo "Would upload in $BATCH_COUNT batch(es) of up to $BATCH_SIZE files"
    echo ""
    echo "Files found:"
    for f in "${DICOM_FILES[@]}"; do
        echo "  $f"
    done
    exit 0
fi

# =============================================================================
# Upload Functions
# =============================================================================

# Build multipart/related body from a list of DICOM files
create_multipart_body() {
    local boundary="$1"
    shift
    local temp_file
    temp_file=$(mktemp)

    for dicom_file in "$@"; do
        printf -- '--%s\r\n' "$boundary" >> "$temp_file"
        printf 'Content-Type: application/dicom\r\n' >> "$temp_file"
        printf '\r\n' >> "$temp_file"
        cat "$dicom_file" >> "$temp_file"
        printf '\r\n' >> "$temp_file"
    done

    printf -- '--%s--\r\n' "$boundary" >> "$temp_file"
    echo "$temp_file"
}

# Send a single STOW-RS request and return HTTP code and body
send_stowrs_request() {
    local multipart_file="$1"
    local boundary="$2"

    local curl_args=(
        -s
        -w '\n---HTTP_CODE---\n%{http_code}'
        -X POST
        -H "Content-Type: multipart/related; type=\"application/dicom\"; boundary=${boundary}"
        -H "Accept: application/dicom+json"
        -H "$AUTH_HEADER"
        --data-binary "@${multipart_file}"
        "$ENDPOINT_URL"
    )

    if $VERBOSE; then
        curl_args=(-v "${curl_args[@]:1}")
    fi

    curl "${curl_args[@]}"
}

# Parse the response to extract HTTP code, success count, failure count
parse_response() {
    local response="$1"
    local http_code
    http_code=$(echo "$response" | grep -A1 "^---HTTP_CODE---$" | tail -1)
    local body
    body=$(echo "$response" | sed '/^---HTTP_CODE---$/,$d')
    echo "${http_code}|${body}"
}

count_instances() {
    local body="$1"
    local tag="$2"  # 00081199 for success, 00081198 for failure
    echo "$body" | python3 -c "
import json, sys
try:
    data = json.load(sys.stdin)
    count = 0
    items = data if isinstance(data, list) else [data]
    for study in items:
        if '$tag' in study and 'Value' in study['$tag']:
            count += len(study['$tag']['Value'])
    print(count)
except:
    print(0)
" 2>/dev/null
}

# =============================================================================
# Upload in Batches
# =============================================================================

BATCH_COUNT=$(( (FILE_COUNT + BATCH_SIZE - 1) / BATCH_SIZE ))
TOTAL_SUCCESS=0
TOTAL_FAILED=0
TOTAL_ERRORS=0

echo -e "${CYAN}Uploading $FILE_COUNT file(s) in $BATCH_COUNT batch(es)...${NC}"
echo ""

for (( batch=0; batch < BATCH_COUNT; batch++ )); do
    start=$(( batch * BATCH_SIZE ))
    end=$(( start + BATCH_SIZE ))
    if [ $end -gt $FILE_COUNT ]; then
        end=$FILE_COUNT
    fi
    batch_files=("${DICOM_FILES[@]:$start:$((end - start))}")
    batch_num=$(( batch + 1 ))
    batch_file_count=${#batch_files[@]}

    echo -e "${CYAN}--- Batch $batch_num/$BATCH_COUNT ($batch_file_count files) ---${NC}"

    BOUNDARY="stowrs_batch_${batch_num}_$$_$(date +%s)"
    MULTIPART_FILE=$(create_multipart_body "$BOUNDARY" "${batch_files[@]}")
    MULTIPART_SIZE=$(stat -c%s "$MULTIPART_FILE" 2>/dev/null || stat -f%z "$MULTIPART_FILE" 2>/dev/null)

    echo "  Payload size: $(numfmt --to=iec "$MULTIPART_SIZE" 2>/dev/null || echo "${MULTIPART_SIZE} bytes")"

    RESPONSE=$(send_stowrs_request "$MULTIPART_FILE" "$BOUNDARY")
    PARSED=$(parse_response "$RESPONSE")
    HTTP_CODE=$(echo "$PARSED" | cut -d'|' -f1)
    BODY=$(echo "$PARSED" | cut -d'|' -f2-)

    rm -f "$MULTIPART_FILE"

    if $VERBOSE; then
        echo "  Response body:"
        echo "$BODY" | python3 -m json.tool 2>/dev/null || echo "$BODY"
        echo ""
    fi

    if [ "$HTTP_CODE" = "200" ] || [ "$HTTP_CODE" = "202" ]; then
        SUCCESS=$(count_instances "$BODY" "00081199")
        FAILED=$(count_instances "$BODY" "00081198")
        TOTAL_SUCCESS=$(( TOTAL_SUCCESS + SUCCESS ))
        TOTAL_FAILED=$(( TOTAL_FAILED + FAILED ))

        if [ "$FAILED" -eq 0 ]; then
            echo -e "  ${GREEN}HTTP $HTTP_CODE - $SUCCESS stored successfully${NC}"
        else
            echo -e "  ${YELLOW}HTTP $HTTP_CODE - $SUCCESS stored, $FAILED failed${NC}"
        fi
    else
        TOTAL_ERRORS=$(( TOTAL_ERRORS + batch_file_count ))
        echo -e "  ${RED}HTTP $HTTP_CODE - Batch failed${NC}"
        if $VERBOSE; then
            echo "  Error: $BODY"
        else
            # Show a short error summary even in non-verbose mode
            ERROR_MSG=$(echo "$BODY" | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
    print(d.get('message', str(d)[:200]))
except:
    print(sys.stdin.read()[:200])
" 2>/dev/null)
            [ -n "$ERROR_MSG" ] && echo "  Error: $ERROR_MSG"
        fi
    fi
done

# =============================================================================
# Summary
# =============================================================================

echo ""
echo -e "${CYAN}=== Upload Summary ===${NC}"
echo ""
echo "  Files scanned:  $FILE_COUNT"
echo -e "  Stored:         ${GREEN}$TOTAL_SUCCESS${NC}"
if [ "$TOTAL_FAILED" -gt 0 ]; then
    echo -e "  Failed:         ${YELLOW}$TOTAL_FAILED${NC}"
else
    echo -e "  Failed:         $TOTAL_FAILED"
fi
if [ "$TOTAL_ERRORS" -gt 0 ]; then
    echo -e "  Errors:         ${RED}$TOTAL_ERRORS${NC} (batch-level HTTP errors)"
else
    echo -e "  Errors:         $TOTAL_ERRORS"
fi
echo ""

if [ "$TOTAL_FAILED" -eq 0 ] && [ "$TOTAL_ERRORS" -eq 0 ]; then
    echo -e "${GREEN}All files uploaded successfully.${NC}"
    exit 0
elif [ "$TOTAL_SUCCESS" -gt 0 ]; then
    echo -e "${YELLOW}Upload completed with some failures.${NC}"
    exit 1
else
    echo -e "${RED}Upload failed.${NC}"
    exit 2
fi
