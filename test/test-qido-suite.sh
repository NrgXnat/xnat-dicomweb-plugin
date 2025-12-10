#!/bin/bash

# =============================================================================
# QIDO-RS Comprehensive Test Suite
# =============================================================================
# Tests all QIDO-RS functionality including:
# 1. Search empty project
# 2. Upload test data (prerequisite)
# 3. Search studies (basic)
# 4. Search with query parameters
# 5. Pagination (limit/offset)
# 6. Hierarchical search (study → series → instances)
# 7. X-Total-Count header verification
# =============================================================================

set -e

BASE_URL="http://localhost:8080"
AUTH="admin:admin"
RESULTS_DIR="/tmp/qido_test_suite_$(date +%s)"
PROJECT="qido_test_$(date +%s)"

# Test data directories
MR_DIR="$HOME/Downloads/STS_045_MR/1"
CT_DIR="$HOME/Downloads/bbbb_CT_524/scans/4-C_A_P/resources/DICOM/files"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Counters
TOTAL_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0

# Captured UIDs for hierarchical tests
STUDY_UID=""
SERIES_UID=""
INSTANCE_UID=""

mkdir -p "$RESULTS_DIR"

# =============================================================================
# Helper Functions
# =============================================================================

log_header() {
    echo ""
    echo "==========================================================================="
    echo "$1"
    echo "==========================================================================="
}

log_subheader() {
    echo ""
    echo -e "${BLUE}--- $1 ---${NC}"
}

log_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

log_failure() {
    echo -e "${RED}✗ $1${NC}"
}

log_warning() {
    echo -e "${YELLOW}⚠ $1${NC}"
}

# Create multipart body from DICOM files
create_multipart_body() {
    local boundary="$1"
    shift
    local temp_file=$(mktemp)

    for dicom_file in "$@"; do
        if [ -f "$dicom_file" ]; then
            printf -- '--%s\r\n' "$boundary" >> "$temp_file"
            printf 'Content-Type: application/dicom\r\n' >> "$temp_file"
            printf '\r\n' >> "$temp_file"
            cat "$dicom_file" >> "$temp_file"
            printf '\r\n' >> "$temp_file"
        fi
    done

    printf -- '--%s--\r\n' "$boundary" >> "$temp_file"
    echo "$temp_file"
}

# Run a test and record result
run_test() {
    local test_name="$1"
    local passed="$2"
    local details="$3"

    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    if [ "$passed" = "true" ]; then
        PASSED_TESTS=$((PASSED_TESTS + 1))
        log_success "$test_name"
    else
        FAILED_TESTS=$((FAILED_TESTS + 1))
        log_failure "$test_name: $details"
    fi
}

# Extract JSON array length using Python
json_array_length() {
    echo "$1" | python3 -c "import json,sys; d=json.load(sys.stdin); print(len(d) if isinstance(d,list) else 0)" 2>/dev/null || echo "0"
}

# Extract value from JSON response
json_get_value() {
    local json="$1"
    local path="$2"
    echo "$json" | python3 -c "
import json,sys
try:
    d=json.load(sys.stdin)
    if isinstance(d, list) and len(d) > 0:
        d = d[0]
    keys = '$path'.split('.')
    for k in keys:
        if k in d:
            d = d[k]
            if isinstance(d, dict) and 'Value' in d:
                d = d['Value'][0] if d['Value'] else ''
        else:
            d = ''
            break
    print(d)
except:
    print('')
" 2>/dev/null
}

# =============================================================================
# Setup
# =============================================================================

log_header "QIDO-RS Test Suite Setup"

echo "Results directory: $RESULTS_DIR"
echo "Test project: $PROJECT"
echo ""

# Create test project
echo "Creating test project..."
CREATE_CODE=$(curl -s -w "%{http_code}" -o /dev/null -u "$AUTH" -X PUT "${BASE_URL}/data/projects/${PROJECT}")
if [ "$CREATE_CODE" = "200" ] || [ "$CREATE_CODE" = "201" ]; then
    log_success "Project created: $PROJECT"
else
    log_failure "Failed to create project (HTTP $CREATE_CODE)"
    exit 1
fi

# Verify test data exists
echo ""
echo "Verifying test data..."
MR_FILES=($(ls "${MR_DIR}"/*.dcm 2>/dev/null | head -5))
CT_FILES=($(ls "${CT_DIR}"/*.dcm 2>/dev/null | head -5))

if [ ${#MR_FILES[@]} -eq 0 ]; then
    log_failure "No MR files found in $MR_DIR"
    exit 1
fi
if [ ${#CT_FILES[@]} -eq 0 ]; then
    log_failure "No CT files found in $CT_DIR"
    exit 1
fi
log_success "Found ${#MR_FILES[@]} MR files, ${#CT_FILES[@]} CT files"

# =============================================================================
# Test 1: Search Empty Project
# =============================================================================

log_header "Test 1: Search Empty Project"

RESPONSE=$(curl -s -w "\n---HTTP_CODE---\n%{http_code}" \
    -H "Accept: application/dicom+json" \
    -u "$AUTH" \
    "${BASE_URL}/xapi/dicomweb/projects/${PROJECT}/studies")

HTTP_CODE=$(echo "$RESPONSE" | grep -A1 "^---HTTP_CODE---$" | tail -1)
BODY=$(echo "$RESPONSE" | sed '/^---HTTP_CODE---$/,$d')

echo "$BODY" > "$RESULTS_DIR/test1_empty_search.json"

echo "HTTP: $HTTP_CODE"
echo "Response: $BODY"

if [ "$HTTP_CODE" = "200" ] && [ "$BODY" = "[]" ]; then
    run_test "Empty project returns empty array" "true"
else
    run_test "Empty project returns empty array" "false" "HTTP=$HTTP_CODE, Body=$BODY"
fi

# =============================================================================
# Test 2: Upload Test Data (Prerequisite)
# =============================================================================

log_header "Test 2: Upload Test Data"

BOUNDARY="qido_upload_$(date +%s)"
ALL_FILES=("${MR_FILES[@]}" "${CT_FILES[@]}")
MULTIPART_FILE=$(create_multipart_body "$BOUNDARY" "${ALL_FILES[@]}")

echo "Uploading ${#ALL_FILES[@]} DICOM files..."
UPLOAD_RESPONSE=$(curl -s -w "\n---HTTP_CODE---\n%{http_code}" \
    -X POST \
    -H "Content-Type: multipart/related; type=\"application/dicom\"; boundary=${BOUNDARY}" \
    -H "Accept: application/dicom+json" \
    --data-binary @"$MULTIPART_FILE" \
    -u "$AUTH" \
    "${BASE_URL}/xapi/dicomweb/projects/${PROJECT}/studies")

UPLOAD_CODE=$(echo "$UPLOAD_RESPONSE" | grep -A1 "^---HTTP_CODE---$" | tail -1)
UPLOAD_BODY=$(echo "$UPLOAD_RESPONSE" | sed '/^---HTTP_CODE---$/,$d')

echo "$UPLOAD_BODY" > "$RESULTS_DIR/test2_upload.json"
rm -f "$MULTIPART_FILE"

echo "Upload HTTP: $UPLOAD_CODE"

if [ "$UPLOAD_CODE" = "200" ]; then
    run_test "Test data uploaded successfully" "true"
else
    run_test "Test data uploaded successfully" "false" "HTTP=$UPLOAD_CODE"
    log_failure "Cannot continue without test data"
    exit 1
fi

# Wait for data to be indexed
echo "Waiting for data indexing..."
sleep 2

# =============================================================================
# Test 3: Search Studies (Basic)
# =============================================================================

log_header "Test 3: Search Studies (Basic)"

RESPONSE=$(curl -s -w "\n---HTTP_CODE---\n%{http_code}" \
    -H "Accept: application/dicom+json" \
    -u "$AUTH" \
    "${BASE_URL}/xapi/dicomweb/projects/${PROJECT}/studies")

HTTP_CODE=$(echo "$RESPONSE" | grep -A1 "^---HTTP_CODE---$" | tail -1)
BODY=$(echo "$RESPONSE" | sed '/^---HTTP_CODE---$/,$d')

echo "$BODY" > "$RESULTS_DIR/test3_search_studies.json"

STUDY_COUNT=$(json_array_length "$BODY")
echo "HTTP: $HTTP_CODE, Studies found: $STUDY_COUNT"

if [ "$HTTP_CODE" = "200" ] && [ "$STUDY_COUNT" -ge 1 ]; then
    run_test "Search studies returns results" "true"

    # Capture Study UID for later tests
    STUDY_UID=$(json_get_value "$BODY" "0020000D")
    echo "Captured StudyInstanceUID: $STUDY_UID"
else
    run_test "Search studies returns results" "false" "Count=$STUDY_COUNT"
fi

# =============================================================================
# Test 4: Search with Query Parameters
# =============================================================================

log_header "Test 4: Search with Query Parameters"

log_subheader "4a: Search by Modality=MR"
RESPONSE=$(curl -s -w "\n---HTTP_CODE---\n%{http_code}" \
    -H "Accept: application/dicom+json" \
    -u "$AUTH" \
    "${BASE_URL}/xapi/dicomweb/projects/${PROJECT}/studies?Modality=MR")

HTTP_CODE=$(echo "$RESPONSE" | grep -A1 "^---HTTP_CODE---$" | tail -1)
BODY=$(echo "$RESPONSE" | sed '/^---HTTP_CODE---$/,$d')

echo "$BODY" > "$RESULTS_DIR/test4a_modality_mr.json"

MR_COUNT=$(json_array_length "$BODY")
echo "HTTP: $HTTP_CODE, MR Studies: $MR_COUNT"

if [ "$HTTP_CODE" = "200" ] && [ "$MR_COUNT" -ge 1 ]; then
    run_test "Search by Modality=MR" "true"
else
    run_test "Search by Modality=MR" "false" "Count=$MR_COUNT"
fi

log_subheader "4b: Search by Modality=CT"
RESPONSE=$(curl -s -w "\n---HTTP_CODE---\n%{http_code}" \
    -H "Accept: application/dicom+json" \
    -u "$AUTH" \
    "${BASE_URL}/xapi/dicomweb/projects/${PROJECT}/studies?Modality=CT")

HTTP_CODE=$(echo "$RESPONSE" | grep -A1 "^---HTTP_CODE---$" | tail -1)
BODY=$(echo "$RESPONSE" | sed '/^---HTTP_CODE---$/,$d')

echo "$BODY" > "$RESULTS_DIR/test4b_modality_ct.json"

CT_COUNT=$(json_array_length "$BODY")
echo "HTTP: $HTTP_CODE, CT Studies: $CT_COUNT"

if [ "$HTTP_CODE" = "200" ] && [ "$CT_COUNT" -ge 1 ]; then
    run_test "Search by Modality=CT" "true"
else
    run_test "Search by Modality=CT" "false" "Count=$CT_COUNT"
fi

log_subheader "4c: Search non-existent modality"
RESPONSE=$(curl -s -w "\n---HTTP_CODE---\n%{http_code}" \
    -H "Accept: application/dicom+json" \
    -u "$AUTH" \
    "${BASE_URL}/xapi/dicomweb/projects/${PROJECT}/studies?Modality=NONEXISTENT")

HTTP_CODE=$(echo "$RESPONSE" | grep -A1 "^---HTTP_CODE---$" | tail -1)
BODY=$(echo "$RESPONSE" | sed '/^---HTTP_CODE---$/,$d')

EMPTY_COUNT=$(json_array_length "$BODY")
echo "HTTP: $HTTP_CODE, Results: $EMPTY_COUNT"

if [ "$HTTP_CODE" = "200" ] && [ "$EMPTY_COUNT" = "0" ]; then
    run_test "Non-existent modality returns empty" "true"
else
    run_test "Non-existent modality returns empty" "false" "Count=$EMPTY_COUNT"
fi

# =============================================================================
# Test 5: Pagination (limit/offset)
# =============================================================================

log_header "Test 5: Pagination"

log_subheader "5a: Limit parameter"
RESPONSE=$(curl -s -w "\n---HTTP_CODE---\n%{http_code}" \
    -D "$RESULTS_DIR/test5a_headers.txt" \
    -H "Accept: application/dicom+json" \
    -u "$AUTH" \
    "${BASE_URL}/xapi/dicomweb/projects/${PROJECT}/studies?limit=1")

HTTP_CODE=$(echo "$RESPONSE" | grep -A1 "^---HTTP_CODE---$" | tail -1)
BODY=$(echo "$RESPONSE" | sed '/^---HTTP_CODE---$/,$d')

echo "$BODY" > "$RESULTS_DIR/test5a_limit.json"

LIMITED_COUNT=$(json_array_length "$BODY")
TOTAL_COUNT=$(grep -i "X-Total-Count" "$RESULTS_DIR/test5a_headers.txt" | awk '{print $2}' | tr -d '\r')

echo "HTTP: $HTTP_CODE, Returned: $LIMITED_COUNT, Total: $TOTAL_COUNT"

if [ "$HTTP_CODE" = "200" ] && [ "$LIMITED_COUNT" = "1" ]; then
    run_test "Limit=1 returns single result" "true"
else
    run_test "Limit=1 returns single result" "false" "Returned=$LIMITED_COUNT"
fi

log_subheader "5b: X-Total-Count header"
if [ -n "$TOTAL_COUNT" ] && [ "$TOTAL_COUNT" -ge 1 ]; then
    run_test "X-Total-Count header present" "true"
else
    run_test "X-Total-Count header present" "false" "Value=$TOTAL_COUNT"
fi

log_subheader "5c: Offset parameter"
# First get all studies
ALL_RESPONSE=$(curl -s -H "Accept: application/dicom+json" -u "$AUTH" \
    "${BASE_URL}/xapi/dicomweb/projects/${PROJECT}/studies")
ALL_COUNT=$(json_array_length "$ALL_RESPONSE")

# Then with offset=1
RESPONSE=$(curl -s -w "\n---HTTP_CODE---\n%{http_code}" \
    -H "Accept: application/dicom+json" \
    -u "$AUTH" \
    "${BASE_URL}/xapi/dicomweb/projects/${PROJECT}/studies?offset=1&limit=100")

HTTP_CODE=$(echo "$RESPONSE" | grep -A1 "^---HTTP_CODE---$" | tail -1)
BODY=$(echo "$RESPONSE" | sed '/^---HTTP_CODE---$/,$d')

OFFSET_COUNT=$(json_array_length "$BODY")
EXPECTED=$((ALL_COUNT - 1))

echo "HTTP: $HTTP_CODE, All: $ALL_COUNT, With offset=1: $OFFSET_COUNT, Expected: $EXPECTED"

if [ "$HTTP_CODE" = "200" ] && [ "$OFFSET_COUNT" = "$EXPECTED" ]; then
    run_test "Offset=1 skips first result" "true"
else
    run_test "Offset=1 skips first result" "false" "Expected=$EXPECTED, Got=$OFFSET_COUNT"
fi

# =============================================================================
# Test 6: Hierarchical Search (Study → Series → Instances)
# =============================================================================

log_header "Test 6: Hierarchical Search"

if [ -z "$STUDY_UID" ]; then
    log_warning "No Study UID captured, skipping hierarchical tests"
else
    log_subheader "6a: Search Series in Study"
    RESPONSE=$(curl -s -w "\n---HTTP_CODE---\n%{http_code}" \
        -H "Accept: application/dicom+json" \
        -u "$AUTH" \
        "${BASE_URL}/xapi/dicomweb/projects/${PROJECT}/studies/${STUDY_UID}/series")

    HTTP_CODE=$(echo "$RESPONSE" | grep -A1 "^---HTTP_CODE---$" | tail -1)
    BODY=$(echo "$RESPONSE" | sed '/^---HTTP_CODE---$/,$d')

    echo "$BODY" > "$RESULTS_DIR/test6a_series.json"

    SERIES_COUNT=$(json_array_length "$BODY")
    echo "HTTP: $HTTP_CODE, Series found: $SERIES_COUNT"

    if [ "$HTTP_CODE" = "200" ] && [ "$SERIES_COUNT" -ge 1 ]; then
        run_test "Search series in study" "true"

        # Capture Series UID for instance search
        SERIES_UID=$(json_get_value "$BODY" "0020000E")
        echo "Captured SeriesInstanceUID: $SERIES_UID"
    else
        run_test "Search series in study" "false" "Count=$SERIES_COUNT"
    fi

    log_subheader "6b: Search Instances in Series"
    if [ -n "$SERIES_UID" ]; then
        RESPONSE=$(curl -s -w "\n---HTTP_CODE---\n%{http_code}" \
            -H "Accept: application/dicom+json" \
            -u "$AUTH" \
            "${BASE_URL}/xapi/dicomweb/projects/${PROJECT}/studies/${STUDY_UID}/series/${SERIES_UID}/instances")

        HTTP_CODE=$(echo "$RESPONSE" | grep -A1 "^---HTTP_CODE---$" | tail -1)
        BODY=$(echo "$RESPONSE" | sed '/^---HTTP_CODE---$/,$d')

        echo "$BODY" > "$RESULTS_DIR/test6b_instances.json"

        INSTANCE_COUNT=$(json_array_length "$BODY")
        echo "HTTP: $HTTP_CODE, Instances found: $INSTANCE_COUNT"

        if [ "$HTTP_CODE" = "200" ] && [ "$INSTANCE_COUNT" -ge 1 ]; then
            run_test "Search instances in series" "true"

            # Capture Instance UID
            INSTANCE_UID=$(json_get_value "$BODY" "00080018")
            echo "Captured SOPInstanceUID: $INSTANCE_UID"
        else
            run_test "Search instances in series" "false" "Count=$INSTANCE_COUNT"
        fi
    else
        log_warning "No Series UID, skipping instance search"
    fi

    log_subheader "6c: Series pagination"
    RESPONSE=$(curl -s -w "\n---HTTP_CODE---\n%{http_code}" \
        -D "$RESULTS_DIR/test6c_headers.txt" \
        -H "Accept: application/dicom+json" \
        -u "$AUTH" \
        "${BASE_URL}/xapi/dicomweb/projects/${PROJECT}/studies/${STUDY_UID}/series?limit=1")

    HTTP_CODE=$(echo "$RESPONSE" | grep -A1 "^---HTTP_CODE---$" | tail -1)
    BODY=$(echo "$RESPONSE" | sed '/^---HTTP_CODE---$/,$d')

    LIMITED_SERIES=$(json_array_length "$BODY")
    TOTAL_SERIES=$(grep -i "X-Total-Count" "$RESULTS_DIR/test6c_headers.txt" | awk '{print $2}' | tr -d '\r')

    echo "HTTP: $HTTP_CODE, Limited: $LIMITED_SERIES, Total: $TOTAL_SERIES"

    if [ "$HTTP_CODE" = "200" ] && [ "$LIMITED_SERIES" = "1" ]; then
        run_test "Series pagination (limit=1)" "true"
    else
        run_test "Series pagination (limit=1)" "false" "Count=$LIMITED_SERIES"
    fi
fi

# =============================================================================
# Test 7: Content-Type Verification
# =============================================================================

log_header "Test 7: Content-Type Verification"

RESPONSE=$(curl -s -D "$RESULTS_DIR/test7_headers.txt" \
    -H "Accept: application/dicom+json" \
    -u "$AUTH" \
    "${BASE_URL}/xapi/dicomweb/projects/${PROJECT}/studies")

CONTENT_TYPE=$(grep -i "^Content-Type:" "$RESULTS_DIR/test7_headers.txt" | head -1)
echo "Content-Type: $CONTENT_TYPE"

if echo "$CONTENT_TYPE" | grep -qi "application/dicom+json"; then
    run_test "Content-Type is application/dicom+json" "true"
else
    run_test "Content-Type is application/dicom+json" "false" "$CONTENT_TYPE"
fi

# =============================================================================
# Summary
# =============================================================================

log_header "Test Suite Summary"

echo ""
echo "Total Tests:  $TOTAL_TESTS"
echo -e "Passed:       ${GREEN}$PASSED_TESTS${NC}"
echo -e "Failed:       ${RED}$FAILED_TESTS${NC}"
echo ""

if [ $FAILED_TESTS -eq 0 ]; then
    echo -e "${GREEN}All tests passed!${NC}"
else
    echo -e "${RED}Some tests failed. Check results in: $RESULTS_DIR${NC}"
fi

echo ""
echo "Results saved in: $RESULTS_DIR"
echo "Test project: $PROJECT"
echo "View in XNAT: ${BASE_URL}/data/projects/${PROJECT}"
echo ""

# Captured UIDs for reference
echo "Captured UIDs:"
echo "  StudyInstanceUID:  $STUDY_UID"
echo "  SeriesInstanceUID: $SERIES_UID"
echo "  SOPInstanceUID:    $INSTANCE_UID"
echo ""

# Exit with error code if any tests failed
exit $FAILED_TESTS
