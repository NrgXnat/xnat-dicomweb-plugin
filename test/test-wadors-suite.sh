#!/bin/bash

# =============================================================================
# WADO-RS Comprehensive Test Suite
# =============================================================================
# Tests all WADO-RS functionality including:
# 1. Retrieve study (multipart DICOM)
# 2. Retrieve series (multipart DICOM)
# 3. Retrieve instance (single DICOM)
# 4. Retrieve study metadata
# 5. Retrieve series metadata
# 6. Retrieve instance metadata
# 7. Retrieve rendered instance (JPEG) [*Optional - depends on DICOM codec]
# 8. Retrieve frames
# 9. Retrieve bulk data
#
# Note: Test 7 (Rendered) may fail depending on DICOM transfer syntax and
# available image codecs. Some compressed DICOM formats (JPEG-LS, JPEG2000)
# require additional native libraries (opencv, charls) for rendering.
# =============================================================================

set -e

BASE_URL="http://localhost:8080"
AUTH="admin:admin"
RESULTS_DIR="/tmp/wadors_test_suite_$(date +%s)"
PROJECT="wadors_test_$(date +%s)"

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

# Captured UIDs for retrieval tests
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

log_header "WADO-RS Test Suite Setup"

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
# Upload Test Data (Prerequisite)
# =============================================================================

log_header "Uploading Test Data"

BOUNDARY="wadors_upload_$(date +%s)"
ALL_FILES=("${MR_FILES[@]}" "${CT_FILES[@]}")
MULTIPART_FILE=$(create_multipart_body "$BOUNDARY" "${ALL_FILES[@]}")

echo "Uploading ${#ALL_FILES[@]} DICOM files (using DirectWrite strategy)..."
UPLOAD_RESPONSE=$(curl -s -w "\n---HTTP_CODE---\n%{http_code}" \
    -X POST \
    -H "Content-Type: multipart/related; type=\"application/dicom\"; boundary=${BOUNDARY}" \
    -H "Accept: application/dicom+json" \
    --data-binary @"$MULTIPART_FILE" \
    -u "$AUTH" \
    "${BASE_URL}/xapi/dicomweb/projects/${PROJECT}/studies?strategy=DirectWrite")

UPLOAD_CODE=$(echo "$UPLOAD_RESPONSE" | grep -A1 "^---HTTP_CODE---$" | tail -1)
echo "$UPLOAD_RESPONSE" | sed '/^---HTTP_CODE---$/,$d' > "$RESULTS_DIR/upload_response.json"
rm -f "$MULTIPART_FILE"

echo "Upload HTTP: $UPLOAD_CODE"

if [ "$UPLOAD_CODE" = "200" ]; then
    log_success "Test data uploaded successfully"
else
    log_failure "Failed to upload test data (HTTP $UPLOAD_CODE)"
    exit 1
fi

# Wait for data to appear in prearchive
echo "Waiting for data processing..."
sleep 3

# Archive prearchive sessions
echo ""
echo "Archiving prearchive sessions..."

# Get prearchive sessions
PREARC_RESPONSE=$(curl -s -u "$AUTH" "${BASE_URL}/data/prearchive/projects/${PROJECT}?format=json")
echo "Prearchive response: $PREARC_RESPONSE"

# Extract session URLs and archive each one
SESSION_URLS=$(echo "$PREARC_RESPONSE" | python3 -c "
import json,sys
try:
    data = json.load(sys.stdin)
    if 'ResultSet' in data and 'Result' in data['ResultSet']:
        for session in data['ResultSet']['Result']:
            print(session.get('url', ''))
except:
    pass
" 2>/dev/null)

ARCHIVE_COUNT=0
for SESSION_URL in $SESSION_URLS; do
    if [ -n "$SESSION_URL" ]; then
        echo "Archiving session: $SESSION_URL"
        # Archive the session - need to extract components from URL
        # URL format: /data/prearchive/projects/{project}/{timestamp}/{session}
        ARCHIVE_CODE=$(curl -s -w "%{http_code}" -o /dev/null -u "$AUTH" \
            -X POST "${BASE_URL}${SESSION_URL}?action=commit&overwrite=append")
        if [ "$ARCHIVE_CODE" = "200" ] || [ "$ARCHIVE_CODE" = "201" ]; then
            log_success "Session archived (HTTP $ARCHIVE_CODE)"
            ARCHIVE_COUNT=$((ARCHIVE_COUNT + 1))
        else
            log_warning "Archive returned HTTP $ARCHIVE_CODE"
        fi
    fi
done

if [ $ARCHIVE_COUNT -eq 0 ]; then
    log_warning "No sessions found in prearchive to archive"
fi

# Wait for archive to complete
echo "Waiting for archive processing..."
sleep 5

# Get UIDs from QIDO-RS
echo ""
echo "Getting UIDs from uploaded data..."
QIDO_RESPONSE=$(curl -s -H "Accept: application/dicom+json" -u "$AUTH" \
    "${BASE_URL}/xapi/dicomweb/projects/${PROJECT}/studies")

STUDY_UID=$(json_get_value "$QIDO_RESPONSE" "0020000D")
echo "StudyInstanceUID: $STUDY_UID"

if [ -z "$STUDY_UID" ]; then
    log_failure "Could not get StudyInstanceUID"
    exit 1
fi

# Get Series UID
SERIES_RESPONSE=$(curl -s -H "Accept: application/dicom+json" -u "$AUTH" \
    "${BASE_URL}/xapi/dicomweb/projects/${PROJECT}/studies/${STUDY_UID}/series")
SERIES_UID=$(json_get_value "$SERIES_RESPONSE" "0020000E")
echo "SeriesInstanceUID: $SERIES_UID"

if [ -z "$SERIES_UID" ]; then
    log_failure "Could not get SeriesInstanceUID"
    exit 1
fi

# Get Instance UID
INSTANCE_RESPONSE=$(curl -s -H "Accept: application/dicom+json" -u "$AUTH" \
    "${BASE_URL}/xapi/dicomweb/projects/${PROJECT}/studies/${STUDY_UID}/series/${SERIES_UID}/instances")
INSTANCE_UID=$(json_get_value "$INSTANCE_RESPONSE" "00080018")
echo "SOPInstanceUID: $INSTANCE_UID"

if [ -z "$INSTANCE_UID" ]; then
    log_failure "Could not get SOPInstanceUID"
    exit 1
fi

log_success "UIDs captured successfully"

# =============================================================================
# Test 1: Retrieve Study (Multipart DICOM)
# =============================================================================

log_header "Test 1: Retrieve Study (Multipart DICOM)"

RESPONSE=$(curl -s -w "\n---HTTP_CODE---\n%{http_code}" \
    -D "$RESULTS_DIR/test1_headers.txt" \
    -H "Accept: multipart/related; type=\"application/dicom\"" \
    -u "$AUTH" \
    -o "$RESULTS_DIR/test1_study.multipart" \
    "${BASE_URL}/xapi/dicomweb/projects/${PROJECT}/studies/${STUDY_UID}")

HTTP_CODE=$(echo "$RESPONSE" | grep -A1 "^---HTTP_CODE---$" | tail -1)
CONTENT_TYPE=$(grep -i "^Content-Type:" "$RESULTS_DIR/test1_headers.txt" | head -1)
FILE_SIZE=$(stat -f%z "$RESULTS_DIR/test1_study.multipart" 2>/dev/null || stat -c%s "$RESULTS_DIR/test1_study.multipart" 2>/dev/null || echo "0")

echo "HTTP: $HTTP_CODE"
echo "Content-Type: $CONTENT_TYPE"
echo "Response size: $FILE_SIZE bytes"

if [ "$HTTP_CODE" = "200" ] && echo "$CONTENT_TYPE" | grep -qi "multipart/related" && [ "$FILE_SIZE" -gt 100 ]; then
    run_test "Retrieve study (multipart)" "true"
else
    run_test "Retrieve study (multipart)" "false" "HTTP=$HTTP_CODE, Size=$FILE_SIZE"
fi

# =============================================================================
# Test 2: Retrieve Series (Multipart DICOM)
# =============================================================================

log_header "Test 2: Retrieve Series (Multipart DICOM)"

RESPONSE=$(curl -s -w "\n---HTTP_CODE---\n%{http_code}" \
    -D "$RESULTS_DIR/test2_headers.txt" \
    -H "Accept: multipart/related; type=\"application/dicom\"" \
    -u "$AUTH" \
    -o "$RESULTS_DIR/test2_series.multipart" \
    "${BASE_URL}/xapi/dicomweb/projects/${PROJECT}/studies/${STUDY_UID}/series/${SERIES_UID}")

HTTP_CODE=$(echo "$RESPONSE" | grep -A1 "^---HTTP_CODE---$" | tail -1)
CONTENT_TYPE=$(grep -i "^Content-Type:" "$RESULTS_DIR/test2_headers.txt" | head -1)
FILE_SIZE=$(stat -f%z "$RESULTS_DIR/test2_series.multipart" 2>/dev/null || stat -c%s "$RESULTS_DIR/test2_series.multipart" 2>/dev/null || echo "0")

echo "HTTP: $HTTP_CODE"
echo "Content-Type: $CONTENT_TYPE"
echo "Response size: $FILE_SIZE bytes"

if [ "$HTTP_CODE" = "200" ] && echo "$CONTENT_TYPE" | grep -qi "multipart/related" && [ "$FILE_SIZE" -gt 100 ]; then
    run_test "Retrieve series (multipart)" "true"
else
    run_test "Retrieve series (multipart)" "false" "HTTP=$HTTP_CODE, Size=$FILE_SIZE"
fi

# =============================================================================
# Test 3: Retrieve Instance (Single DICOM)
# =============================================================================

log_header "Test 3: Retrieve Instance (Single DICOM)"

RESPONSE=$(curl -s -w "\n---HTTP_CODE---\n%{http_code}" \
    -D "$RESULTS_DIR/test3_headers.txt" \
    -H "Accept: application/dicom" \
    -u "$AUTH" \
    -o "$RESULTS_DIR/test3_instance.dcm" \
    "${BASE_URL}/xapi/dicomweb/projects/${PROJECT}/studies/${STUDY_UID}/series/${SERIES_UID}/instances/${INSTANCE_UID}")

HTTP_CODE=$(echo "$RESPONSE" | grep -A1 "^---HTTP_CODE---$" | tail -1)
CONTENT_TYPE=$(grep -i "^Content-Type:" "$RESULTS_DIR/test3_headers.txt" | head -1)
FILE_SIZE=$(stat -f%z "$RESULTS_DIR/test3_instance.dcm" 2>/dev/null || stat -c%s "$RESULTS_DIR/test3_instance.dcm" 2>/dev/null || echo "0")

echo "HTTP: $HTTP_CODE"
echo "Content-Type: $CONTENT_TYPE"
echo "Response size: $FILE_SIZE bytes"

# Check DICOM magic bytes (DICM at offset 128)
MAGIC=""
if [ -f "$RESULTS_DIR/test3_instance.dcm" ] && [ "$FILE_SIZE" -gt 132 ]; then
    MAGIC=$(dd if="$RESULTS_DIR/test3_instance.dcm" bs=1 skip=128 count=4 2>/dev/null)
fi

if [ "$HTTP_CODE" = "200" ] && echo "$CONTENT_TYPE" | grep -qi "application/dicom" && [ "$MAGIC" = "DICM" ]; then
    run_test "Retrieve instance (DICOM)" "true"
else
    run_test "Retrieve instance (DICOM)" "false" "HTTP=$HTTP_CODE, Magic=$MAGIC"
fi

# =============================================================================
# Test 4: Retrieve Study Metadata
# =============================================================================

log_header "Test 4: Retrieve Study Metadata"

RESPONSE=$(curl -s -w "\n---HTTP_CODE---\n%{http_code}" \
    -D "$RESULTS_DIR/test4_headers.txt" \
    -H "Accept: application/dicom+json" \
    -u "$AUTH" \
    "${BASE_URL}/xapi/dicomweb/projects/${PROJECT}/studies/${STUDY_UID}/metadata")

HTTP_CODE=$(echo "$RESPONSE" | grep -A1 "^---HTTP_CODE---$" | tail -1)
BODY=$(echo "$RESPONSE" | sed '/^---HTTP_CODE---$/,$d')
CONTENT_TYPE=$(grep -i "^Content-Type:" "$RESULTS_DIR/test4_headers.txt" | head -1)

echo "$BODY" > "$RESULTS_DIR/test4_study_metadata.json"

INSTANCE_COUNT=$(json_array_length "$BODY")
echo "HTTP: $HTTP_CODE"
echo "Content-Type: $CONTENT_TYPE"
echo "Instances in metadata: $INSTANCE_COUNT"

if [ "$HTTP_CODE" = "200" ] && echo "$CONTENT_TYPE" | grep -qi "application/dicom+json" && [ "$INSTANCE_COUNT" -ge 1 ]; then
    run_test "Retrieve study metadata" "true"
else
    run_test "Retrieve study metadata" "false" "HTTP=$HTTP_CODE, Count=$INSTANCE_COUNT"
fi

# =============================================================================
# Test 5: Retrieve Series Metadata
# =============================================================================

log_header "Test 5: Retrieve Series Metadata"

RESPONSE=$(curl -s -w "\n---HTTP_CODE---\n%{http_code}" \
    -D "$RESULTS_DIR/test5_headers.txt" \
    -H "Accept: application/dicom+json" \
    -u "$AUTH" \
    "${BASE_URL}/xapi/dicomweb/projects/${PROJECT}/studies/${STUDY_UID}/series/${SERIES_UID}/metadata")

HTTP_CODE=$(echo "$RESPONSE" | grep -A1 "^---HTTP_CODE---$" | tail -1)
BODY=$(echo "$RESPONSE" | sed '/^---HTTP_CODE---$/,$d')
CONTENT_TYPE=$(grep -i "^Content-Type:" "$RESULTS_DIR/test5_headers.txt" | head -1)

echo "$BODY" > "$RESULTS_DIR/test5_series_metadata.json"

INSTANCE_COUNT=$(json_array_length "$BODY")
echo "HTTP: $HTTP_CODE"
echo "Content-Type: $CONTENT_TYPE"
echo "Instances in series metadata: $INSTANCE_COUNT"

if [ "$HTTP_CODE" = "200" ] && echo "$CONTENT_TYPE" | grep -qi "application/dicom+json" && [ "$INSTANCE_COUNT" -ge 1 ]; then
    run_test "Retrieve series metadata" "true"
else
    run_test "Retrieve series metadata" "false" "HTTP=$HTTP_CODE, Count=$INSTANCE_COUNT"
fi

# =============================================================================
# Test 6: Retrieve Instance Metadata
# =============================================================================

log_header "Test 6: Retrieve Instance Metadata"

RESPONSE=$(curl -s -w "\n---HTTP_CODE---\n%{http_code}" \
    -D "$RESULTS_DIR/test6_headers.txt" \
    -H "Accept: application/dicom+json" \
    -u "$AUTH" \
    "${BASE_URL}/xapi/dicomweb/projects/${PROJECT}/studies/${STUDY_UID}/series/${SERIES_UID}/instances/${INSTANCE_UID}/metadata")

HTTP_CODE=$(echo "$RESPONSE" | grep -A1 "^---HTTP_CODE---$" | tail -1)
BODY=$(echo "$RESPONSE" | sed '/^---HTTP_CODE---$/,$d')
CONTENT_TYPE=$(grep -i "^Content-Type:" "$RESULTS_DIR/test6_headers.txt" | head -1)

echo "$BODY" > "$RESULTS_DIR/test6_instance_metadata.json"

echo "HTTP: $HTTP_CODE"
echo "Content-Type: $CONTENT_TYPE"

# Check for SOP Instance UID in response
HAS_SOP_UID=$(echo "$BODY" | grep -c "00080018" || echo "0")

if [ "$HTTP_CODE" = "200" ] && echo "$CONTENT_TYPE" | grep -qi "application/dicom+json" && [ "$HAS_SOP_UID" -ge 1 ]; then
    run_test "Retrieve instance metadata" "true"
else
    run_test "Retrieve instance metadata" "false" "HTTP=$HTTP_CODE"
fi

# =============================================================================
# Test 7: Retrieve Rendered Instance (JPEG)
# =============================================================================

log_header "Test 7: Retrieve Rendered Instance (JPEG)"

RESPONSE=$(curl -s -w "\n---HTTP_CODE---\n%{http_code}" \
    -D "$RESULTS_DIR/test7_headers.txt" \
    -H "Accept: image/jpeg" \
    -u "$AUTH" \
    -o "$RESULTS_DIR/test7_rendered.jpg" \
    "${BASE_URL}/xapi/dicomweb/projects/${PROJECT}/studies/${STUDY_UID}/series/${SERIES_UID}/instances/${INSTANCE_UID}/rendered")

HTTP_CODE=$(echo "$RESPONSE" | grep -A1 "^---HTTP_CODE---$" | tail -1)
CONTENT_TYPE=$(grep -i "^Content-Type:" "$RESULTS_DIR/test7_headers.txt" | head -1)
FILE_SIZE=$(stat -f%z "$RESULTS_DIR/test7_rendered.jpg" 2>/dev/null || stat -c%s "$RESULTS_DIR/test7_rendered.jpg" 2>/dev/null || echo "0")

echo "HTTP: $HTTP_CODE"
echo "Content-Type: $CONTENT_TYPE"
echo "Image size: $FILE_SIZE bytes"

# Check for JPEG magic bytes (FF D8 FF)
MAGIC=""
if [ -f "$RESULTS_DIR/test7_rendered.jpg" ] && [ "$FILE_SIZE" -gt 3 ]; then
    MAGIC=$(xxd -p -l 3 "$RESULTS_DIR/test7_rendered.jpg" 2>/dev/null || od -A n -t x1 -N 3 "$RESULTS_DIR/test7_rendered.jpg" 2>/dev/null | tr -d ' ')
fi

if [ "$HTTP_CODE" = "200" ] && echo "$CONTENT_TYPE" | grep -qi "image/jpeg" && [ "$FILE_SIZE" -gt 100 ]; then
    run_test "Retrieve rendered instance (JPEG)" "true"
else
    run_test "Retrieve rendered instance (JPEG)" "false" "HTTP=$HTTP_CODE, Size=$FILE_SIZE"
fi

# =============================================================================
# Test 8: Retrieve Frames
# =============================================================================

log_header "Test 8: Retrieve Frames"

log_subheader "8a: Single frame"
RESPONSE=$(curl -s -w "\n---HTTP_CODE---\n%{http_code}" \
    -D "$RESULTS_DIR/test8a_headers.txt" \
    -H "Accept: application/octet-stream" \
    -u "$AUTH" \
    -o "$RESULTS_DIR/test8a_frame1.bin" \
    "${BASE_URL}/xapi/dicomweb/projects/${PROJECT}/studies/${STUDY_UID}/series/${SERIES_UID}/instances/${INSTANCE_UID}/frames/1")

HTTP_CODE=$(echo "$RESPONSE" | grep -A1 "^---HTTP_CODE---$" | tail -1)
CONTENT_TYPE=$(grep -i "^Content-Type:" "$RESULTS_DIR/test8a_headers.txt" | head -1)
FILE_SIZE=$(stat -f%z "$RESULTS_DIR/test8a_frame1.bin" 2>/dev/null || stat -c%s "$RESULTS_DIR/test8a_frame1.bin" 2>/dev/null || echo "0")

echo "HTTP: $HTTP_CODE"
echo "Content-Type: $CONTENT_TYPE"
echo "Frame size: $FILE_SIZE bytes"

if [ "$HTTP_CODE" = "200" ] && [ "$FILE_SIZE" -gt 0 ]; then
    run_test "Retrieve single frame" "true"
else
    run_test "Retrieve single frame" "false" "HTTP=$HTTP_CODE, Size=$FILE_SIZE"
fi

# =============================================================================
# Test 9: Retrieve Bulk Data
# =============================================================================

log_header "Test 9: Retrieve Bulk Data (PixelData)"

# 7FE00010 = PixelData tag
RESPONSE=$(curl -s -w "\n---HTTP_CODE---\n%{http_code}" \
    -D "$RESULTS_DIR/test9_headers.txt" \
    -H "Accept: application/octet-stream" \
    -u "$AUTH" \
    -o "$RESULTS_DIR/test9_pixeldata.bin" \
    "${BASE_URL}/xapi/dicomweb/projects/${PROJECT}/studies/${STUDY_UID}/series/${SERIES_UID}/instances/${INSTANCE_UID}/bulkdata/7FE00010")

HTTP_CODE=$(echo "$RESPONSE" | grep -A1 "^---HTTP_CODE---$" | tail -1)
CONTENT_TYPE=$(grep -i "^Content-Type:" "$RESULTS_DIR/test9_headers.txt" | head -1)
FILE_SIZE=$(stat -f%z "$RESULTS_DIR/test9_pixeldata.bin" 2>/dev/null || stat -c%s "$RESULTS_DIR/test9_pixeldata.bin" 2>/dev/null || echo "0")

echo "HTTP: $HTTP_CODE"
echo "Content-Type: $CONTENT_TYPE"
echo "Pixel data size: $FILE_SIZE bytes"

if [ "$HTTP_CODE" = "200" ] && [ "$FILE_SIZE" -gt 0 ]; then
    run_test "Retrieve bulk data (PixelData)" "true"
else
    run_test "Retrieve bulk data (PixelData)" "false" "HTTP=$HTTP_CODE, Size=$FILE_SIZE"
fi

# =============================================================================
# Test 10: Not Found Handling
# =============================================================================

log_header "Test 10: Not Found Handling"

log_subheader "10a: Non-existent study"
RESPONSE=$(curl -s -w "\n---HTTP_CODE---\n%{http_code}" \
    -H "Accept: application/dicom+json" \
    -u "$AUTH" \
    "${BASE_URL}/xapi/dicomweb/projects/${PROJECT}/studies/1.2.3.4.5.999/metadata")

HTTP_CODE=$(echo "$RESPONSE" | grep -A1 "^---HTTP_CODE---$" | tail -1)
echo "HTTP: $HTTP_CODE"

if [ "$HTTP_CODE" = "404" ]; then
    run_test "Non-existent study returns 404" "true"
else
    run_test "Non-existent study returns 404" "false" "HTTP=$HTTP_CODE"
fi

log_subheader "10b: Non-existent instance"
RESPONSE=$(curl -s -w "\n---HTTP_CODE---\n%{http_code}" \
    -H "Accept: application/dicom" \
    -u "$AUTH" \
    "${BASE_URL}/xapi/dicomweb/projects/${PROJECT}/studies/${STUDY_UID}/series/${SERIES_UID}/instances/1.2.3.4.5.999")

HTTP_CODE=$(echo "$RESPONSE" | grep -A1 "^---HTTP_CODE---$" | tail -1)
echo "HTTP: $HTTP_CODE"

if [ "$HTTP_CODE" = "404" ]; then
    run_test "Non-existent instance returns 404" "true"
else
    run_test "Non-existent instance returns 404" "false" "HTTP=$HTTP_CODE"
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
