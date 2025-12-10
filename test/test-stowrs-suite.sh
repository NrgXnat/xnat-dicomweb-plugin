#!/bin/bash

# =============================================================================
# STOW-RS Comprehensive Test Suite
# =============================================================================
# Tests all STOW-RS functionality including:
# 1. Single file upload (basic)
# 2. Multi-file same study upload
# 3. Multi-patient single request
# 4. Concurrent uploads
# 5. Invalid file handling (failure scenario)
# 6. DirectArchive strategy
# 7. Mixed success/failure scenario
# =============================================================================

set -e

BASE_URL="http://localhost:8080"
AUTH="admin:admin"
RESULTS_DIR="/tmp/stowrs_test_suite_$(date +%s)"
PROJECT="stowrs_test_$(date +%s)"

# Test data directories
MR_DIR="$HOME/Downloads/STS_045_MR/1"
MR_DIR2="$HOME/Downloads/STS_045_MR/2"
CT_DIR="$HOME/Downloads/bbbb_CT_524/scans/4-C_A_P/resources/DICOM/files"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Counters
TOTAL_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0

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
    echo "-------------------------------------------"
    echo "$1"
    echo "-------------------------------------------"
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
# Usage: create_multipart_body BOUNDARY FILE1 [FILE2 ...]
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

# Send STOW-RS request
# Usage: send_stowrs PROJECT MULTIPART_FILE BOUNDARY [EXTRA_PARAMS]
send_stowrs() {
    local project="$1"
    local multipart_file="$2"
    local boundary="$3"
    local extra_params="$4"

    local url="${BASE_URL}/xapi/dicomweb/projects/${project}/studies"
    if [ -n "$extra_params" ]; then
        url="${url}?${extra_params}"
    fi

    curl -s -w "\n---HTTP_CODE---\n%{http_code}" \
        -X POST \
        -H "Content-Type: multipart/related; type=\"application/dicom\"; boundary=${boundary}" \
        -H "Accept: application/dicom+json" \
        --data-binary @"$multipart_file" \
        -u "$AUTH" \
        "$url"
}

# Parse response and extract info
# Usage: parse_response RESPONSE
parse_response() {
    local response="$1"
    local http_code=$(echo "$response" | grep -A1 "^---HTTP_CODE---$" | tail -1)
    local body=$(echo "$response" | sed '/^---HTTP_CODE---$/,$d')
    echo "$http_code|$body"
}

# Count studies in response
count_studies() {
    local body="$1"
    echo "$body" | python3 -c "
import json, sys
try:
    data = json.load(sys.stdin)
    if isinstance(data, list):
        print(len(data))
    else:
        print(1)
except:
    print(0)
"
}

# Count successful instances in response
count_successful_instances() {
    local body="$1"
    echo "$body" | python3 -c "
import json, sys
try:
    data = json.load(sys.stdin)
    count = 0
    if isinstance(data, list):
        for study in data:
            if '00081199' in study and 'Value' in study['00081199']:
                count += len(study['00081199']['Value'])
    elif '00081199' in data and 'Value' in data['00081199']:
        count = len(data['00081199']['Value'])
    print(count)
except:
    print(0)
"
}

# Count failed instances in response
count_failed_instances() {
    local body="$1"
    echo "$body" | python3 -c "
import json, sys
try:
    data = json.load(sys.stdin)
    count = 0
    if isinstance(data, list):
        for study in data:
            if '00081198' in study and 'Value' in study['00081198']:
                count += len(study['00081198']['Value'])
    elif '00081198' in data and 'Value' in data['00081198']:
        count = len(data['00081198']['Value'])
    print(count)
except:
    print(0)
"
}

# Run a test and record result
# Usage: run_test "Test Name" expected_http expected_studies expected_success expected_failed
run_test() {
    local test_name="$1"
    local expected_http="$2"
    local expected_studies="$3"
    local expected_success="$4"
    local expected_failed="$5"
    local actual_http="$6"
    local actual_studies="$7"
    local actual_success="$8"
    local actual_failed="$9"

    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    local passed=true
    local details=""

    if [ "$actual_http" != "$expected_http" ]; then
        passed=false
        details="HTTP: expected $expected_http, got $actual_http"
    fi

    if [ "$expected_studies" != "-" ] && [ "$actual_studies" != "$expected_studies" ]; then
        passed=false
        details="$details | Studies: expected $expected_studies, got $actual_studies"
    fi

    if [ "$expected_success" != "-" ] && [ "$actual_success" != "$expected_success" ]; then
        passed=false
        details="$details | Success: expected $expected_success, got $actual_success"
    fi

    if [ "$expected_failed" != "-" ] && [ "$actual_failed" != "$expected_failed" ]; then
        passed=false
        details="$details | Failed: expected $expected_failed, got $actual_failed"
    fi

    if $passed; then
        PASSED_TESTS=$((PASSED_TESTS + 1))
        log_success "$test_name"
    else
        FAILED_TESTS=$((FAILED_TESTS + 1))
        log_failure "$test_name: $details"
    fi
}

# =============================================================================
# Setup
# =============================================================================

log_header "STOW-RS Test Suite Setup"

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
MR_FILES=($(ls "${MR_DIR}"/*.dcm 2>/dev/null | head -3))
MR_FILES2=($(ls "${MR_DIR2}"/*.dcm 2>/dev/null | head -3))
CT_FILES=($(ls "${CT_DIR}"/*.dcm 2>/dev/null | head -3))

if [ ${#MR_FILES[@]} -eq 0 ]; then
    log_failure "No MR files found in $MR_DIR"
    exit 1
fi
if [ ${#CT_FILES[@]} -eq 0 ]; then
    log_failure "No CT files found in $CT_DIR"
    exit 1
fi
log_success "Found ${#MR_FILES[@]} MR files (series 1), ${#MR_FILES2[@]} MR files (series 2), ${#CT_FILES[@]} CT files"

# =============================================================================
# Test 1: Single File Upload (Basic)
# =============================================================================

log_header "Test 1: Single File Upload (Basic)"

BOUNDARY="test1_$(date +%s)"
MULTIPART_FILE=$(create_multipart_body "$BOUNDARY" "${MR_FILES[0]}")

echo "Uploading single DICOM file..."
RESPONSE=$(send_stowrs "$PROJECT" "$MULTIPART_FILE" "$BOUNDARY")
PARSED=$(parse_response "$RESPONSE")
HTTP_CODE=$(echo "$PARSED" | cut -d'|' -f1)
BODY=$(echo "$PARSED" | cut -d'|' -f2-)

echo "$BODY" > "$RESULTS_DIR/test1_response.json"

STUDIES=$(count_studies "$BODY")
SUCCESS=$(count_successful_instances "$BODY")
FAILED=$(count_failed_instances "$BODY")

echo "HTTP: $HTTP_CODE, Studies: $STUDIES, Success: $SUCCESS, Failed: $FAILED"
run_test "Single file upload" "200" "1" "1" "0" "$HTTP_CODE" "$STUDIES" "$SUCCESS" "$FAILED"

rm -f "$MULTIPART_FILE"

# =============================================================================
# Test 2: Multi-file Same Study Upload
# =============================================================================

log_header "Test 2: Multi-file Same Study Upload"

BOUNDARY="test2_$(date +%s)"
MULTIPART_FILE=$(create_multipart_body "$BOUNDARY" "${MR_FILES[@]}")

echo "Uploading ${#MR_FILES[@]} files from same study..."
RESPONSE=$(send_stowrs "$PROJECT" "$MULTIPART_FILE" "$BOUNDARY")
PARSED=$(parse_response "$RESPONSE")
HTTP_CODE=$(echo "$PARSED" | cut -d'|' -f1)
BODY=$(echo "$PARSED" | cut -d'|' -f2-)

echo "$BODY" > "$RESULTS_DIR/test2_response.json"

STUDIES=$(count_studies "$BODY")
SUCCESS=$(count_successful_instances "$BODY")
FAILED=$(count_failed_instances "$BODY")

echo "HTTP: $HTTP_CODE, Studies: $STUDIES, Success: $SUCCESS, Failed: $FAILED"
run_test "Multi-file same study" "200" "1" "${#MR_FILES[@]}" "0" "$HTTP_CODE" "$STUDIES" "$SUCCESS" "$FAILED"

rm -f "$MULTIPART_FILE"

# =============================================================================
# Test 3: Multi-patient Single Request
# =============================================================================

log_header "Test 3: Multi-patient Single Request"

BOUNDARY="test3_$(date +%s)"
ALL_FILES=("${MR_FILES[@]}" "${MR_FILES2[@]}" "${CT_FILES[@]}")
MULTIPART_FILE=$(create_multipart_body "$BOUNDARY" "${ALL_FILES[@]}")

EXPECTED_SUCCESS=$((${#MR_FILES[@]} + ${#MR_FILES2[@]} + ${#CT_FILES[@]}))
echo "Uploading $EXPECTED_SUCCESS files from 2 patients (MR + CT)..."
RESPONSE=$(send_stowrs "$PROJECT" "$MULTIPART_FILE" "$BOUNDARY")
PARSED=$(parse_response "$RESPONSE")
HTTP_CODE=$(echo "$PARSED" | cut -d'|' -f1)
BODY=$(echo "$PARSED" | cut -d'|' -f2-)

echo "$BODY" > "$RESULTS_DIR/test3_response.json"

STUDIES=$(count_studies "$BODY")
SUCCESS=$(count_successful_instances "$BODY")
FAILED=$(count_failed_instances "$BODY")

echo "HTTP: $HTTP_CODE, Studies: $STUDIES, Success: $SUCCESS, Failed: $FAILED"
run_test "Multi-patient single request" "200" "2" "$EXPECTED_SUCCESS" "0" "$HTTP_CODE" "$STUDIES" "$SUCCESS" "$FAILED"

# Verify response structure (array with 2 study objects)
echo ""
echo "Verifying response structure..."
IS_ARRAY=$(echo "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); print('yes' if isinstance(d, list) else 'no')" 2>/dev/null || echo "no")
if [ "$IS_ARRAY" = "yes" ]; then
    log_success "Response is JSON array (grouped by study)"
else
    log_failure "Response should be JSON array"
fi

rm -f "$MULTIPART_FILE"

# =============================================================================
# Test 4: Concurrent Uploads
# =============================================================================

log_header "Test 4: Concurrent Uploads (3 threads)"

CONCURRENT_DIR="$RESULTS_DIR/concurrent"
mkdir -p "$CONCURRENT_DIR"

echo "Starting 3 concurrent uploads..."

for i in 1 2 3; do
    (
        BOUNDARY="test4_${i}_$(date +%s)"
        MULTIPART_FILE=$(create_multipart_body "$BOUNDARY" "${MR_FILES[$((i-1))]}")

        RESPONSE=$(send_stowrs "$PROJECT" "$MULTIPART_FILE" "$BOUNDARY")
        PARSED=$(parse_response "$RESPONSE")
        HTTP_CODE=$(echo "$PARSED" | cut -d'|' -f1)
        BODY=$(echo "$PARSED" | cut -d'|' -f2-)

        echo "$HTTP_CODE" > "$CONCURRENT_DIR/thread${i}_code.txt"
        echo "$BODY" > "$CONCURRENT_DIR/thread${i}_response.json"

        rm -f "$MULTIPART_FILE"
    ) &
done

wait
echo "All threads completed"

# Check results
CONCURRENT_SUCCESS=0
for i in 1 2 3; do
    CODE=$(cat "$CONCURRENT_DIR/thread${i}_code.txt")
    if [ "$CODE" = "200" ]; then
        CONCURRENT_SUCCESS=$((CONCURRENT_SUCCESS + 1))
        log_success "Thread $i: HTTP $CODE"
    else
        log_failure "Thread $i: HTTP $CODE"
    fi
done

TOTAL_TESTS=$((TOTAL_TESTS + 1))
if [ "$CONCURRENT_SUCCESS" -eq 3 ]; then
    PASSED_TESTS=$((PASSED_TESTS + 1))
    log_success "Concurrent upload test (all 3 threads succeeded)"
else
    FAILED_TESTS=$((FAILED_TESTS + 1))
    log_failure "Concurrent upload test ($CONCURRENT_SUCCESS/3 threads succeeded)"
fi

# =============================================================================
# Test 5: Invalid File (Failure Scenario)
# =============================================================================

log_header "Test 5: Invalid File (Non-DICOM)"

BOUNDARY="test5_$(date +%s)"
INVALID_FILE="$RESULTS_DIR/invalid.txt"
echo "This is not a DICOM file" > "$INVALID_FILE"

# Create multipart manually for invalid file
MULTIPART_FILE="$RESULTS_DIR/test5_multipart.dat"
{
    printf -- '--%s\r\n' "$BOUNDARY"
    printf 'Content-Type: application/dicom\r\n'
    printf '\r\n'
    cat "$INVALID_FILE"
    printf '\r\n'
    printf -- '--%s--\r\n' "$BOUNDARY"
} > "$MULTIPART_FILE"

echo "Uploading non-DICOM file..."
RESPONSE=$(send_stowrs "$PROJECT" "$MULTIPART_FILE" "$BOUNDARY")
PARSED=$(parse_response "$RESPONSE")
HTTP_CODE=$(echo "$PARSED" | cut -d'|' -f1)
BODY=$(echo "$PARSED" | cut -d'|' -f2-)

echo "$BODY" > "$RESULTS_DIR/test5_response.json"

echo "HTTP: $HTTP_CODE"
echo "Response: $BODY"

# Expect failure (409 Conflict or 400 Bad Request)
TOTAL_TESTS=$((TOTAL_TESTS + 1))
if [ "$HTTP_CODE" = "409" ] || [ "$HTTP_CODE" = "400" ] || [ "$HTTP_CODE" = "500" ]; then
    PASSED_TESTS=$((PASSED_TESTS + 1))
    log_success "Invalid file rejected with HTTP $HTTP_CODE"
else
    FAILED_TESTS=$((FAILED_TESTS + 1))
    log_failure "Expected rejection for invalid file, got HTTP $HTTP_CODE"
fi

rm -f "$INVALID_FILE" "$MULTIPART_FILE"

# =============================================================================
# Test 6: DirectArchive Strategy (separate project to avoid conflicts)
# =============================================================================

log_header "Test 6: DirectArchive Strategy"

# Create a separate project for DirectArchive test (it doesn't support append)
DA_PROJECT="${PROJECT}_directarchive"
echo "Creating separate project for DirectArchive: $DA_PROJECT"
DA_CREATE_CODE=$(curl -s -w "%{http_code}" -o /dev/null -u "$AUTH" -X PUT "${BASE_URL}/data/projects/${DA_PROJECT}")
if [ "$DA_CREATE_CODE" = "200" ] || [ "$DA_CREATE_CODE" = "201" ]; then
    log_success "DirectArchive project created"
else
    log_warning "Failed to create DirectArchive project (HTTP $DA_CREATE_CODE)"
fi

BOUNDARY="test6_$(date +%s)"
MULTIPART_FILE=$(create_multipart_body "$BOUNDARY" "${CT_FILES[0]}")

echo "Uploading with DirectArchive strategy..."
RESPONSE=$(send_stowrs "$DA_PROJECT" "$MULTIPART_FILE" "$BOUNDARY" "strategy=DirectArchive")
PARSED=$(parse_response "$RESPONSE")
HTTP_CODE=$(echo "$PARSED" | cut -d'|' -f1)
BODY=$(echo "$PARSED" | cut -d'|' -f2-)

echo "$BODY" > "$RESULTS_DIR/test6_response.json"

STUDIES=$(count_studies "$BODY")
SUCCESS=$(count_successful_instances "$BODY")
FAILED=$(count_failed_instances "$BODY")

echo "HTTP: $HTTP_CODE, Studies: $STUDIES, Success: $SUCCESS, Failed: $FAILED"
run_test "DirectArchive strategy" "200" "1" "1" "0" "$HTTP_CODE" "$STUDIES" "$SUCCESS" "$FAILED"

rm -f "$MULTIPART_FILE"

# =============================================================================
# Test 7: Mixed Success/Failure (Valid + Invalid files)
# =============================================================================

log_header "Test 7: Mixed Success/Failure"

BOUNDARY="test7_$(date +%s)"
INVALID_FILE="$RESULTS_DIR/invalid2.txt"
echo "Not DICOM" > "$INVALID_FILE"

# Create multipart with valid DICOM + invalid file
MULTIPART_FILE="$RESULTS_DIR/test7_multipart.dat"
{
    # Valid DICOM file
    printf -- '--%s\r\n' "$BOUNDARY"
    printf 'Content-Type: application/dicom\r\n'
    printf '\r\n'
    cat "${CT_FILES[1]}"
    printf '\r\n'

    # Invalid file
    printf -- '--%s\r\n' "$BOUNDARY"
    printf 'Content-Type: application/dicom\r\n'
    printf '\r\n'
    cat "$INVALID_FILE"
    printf '\r\n'

    printf -- '--%s--\r\n' "$BOUNDARY"
} > "$MULTIPART_FILE"

echo "Uploading 1 valid DICOM + 1 invalid file..."
RESPONSE=$(send_stowrs "$PROJECT" "$MULTIPART_FILE" "$BOUNDARY")
PARSED=$(parse_response "$RESPONSE")
HTTP_CODE=$(echo "$PARSED" | cut -d'|' -f1)
BODY=$(echo "$PARSED" | cut -d'|' -f2-)

echo "$BODY" > "$RESULTS_DIR/test7_response.json"

SUCCESS=$(count_successful_instances "$BODY")
FAILED=$(count_failed_instances "$BODY")

echo "HTTP: $HTTP_CODE, Success: $SUCCESS, Failed: $FAILED"

# Expect partial success (202 Accepted) or success with failures recorded
TOTAL_TESTS=$((TOTAL_TESTS + 1))
if [ "$HTTP_CODE" = "200" ] || [ "$HTTP_CODE" = "202" ]; then
    if [ "$SUCCESS" -ge 1 ]; then
        PASSED_TESTS=$((PASSED_TESTS + 1))
        log_success "Mixed upload handled: $SUCCESS success, $FAILED failed"
    else
        FAILED_TESTS=$((FAILED_TESTS + 1))
        log_failure "Expected at least 1 success"
    fi
else
    FAILED_TESTS=$((FAILED_TESTS + 1))
    log_failure "Unexpected HTTP $HTTP_CODE for mixed upload"
fi

rm -f "$INVALID_FILE" "$MULTIPART_FILE"

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

# Exit with error code if any tests failed
exit $FAILED_TESTS
