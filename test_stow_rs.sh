#!/bin/bash
# Test STOW-RS upload to XNAT DICOMweb plugin
# Usage: ./test_stow_rs.sh [XNAT_URL] [PROJECT_ID] [USERNAME] [PASSWORD]

set -e

# Configuration - all parameters required
XNAT_URL="${1}"
PROJECT_ID="${2}"
USERNAME="${3}"
PASSWORD="${4}"

# Validate required parameters
if [ -z "$XNAT_URL" ] || [ -z "$PROJECT_ID" ] || [ -z "$USERNAME" ] || [ -z "$PASSWORD" ]; then
    echo "Usage: $0 XNAT_URL PROJECT_ID USERNAME PASSWORD"
    echo ""
    echo "Example:"
    echo "  $0 http://your-xnat-server YOUR_PROJECT your_username your_password"
    echo ""
    echo "Arguments:"
    echo "  XNAT_URL      Base URL of XNAT server (e.g., http://your-xnat-server)"
    echo "  PROJECT_ID    Project ID in XNAT"
    echo "  USERNAME      XNAT username"
    echo "  PASSWORD      XNAT password"
    exit 1
fi

# STOW-RS endpoint
STOW_ENDPOINT="${XNAT_URL}/xapi/dicomweb/projects/${PROJECT_ID}/studies"

# Test DICOM file
TEST_DICOM=$(find test/data/2/DICOM -name "*.dcm" -type f | head -1)

if [ ! -f "$TEST_DICOM" ]; then
    echo "ERROR: No test DICOM files found in test/data/2/DICOM/"
    exit 1
fi

echo "=========================================="
echo "STOW-RS Upload Test"
echo "=========================================="
echo "XNAT URL:     $XNAT_URL"
echo "Project:      $PROJECT_ID"
echo "Endpoint:     $STOW_ENDPOINT"
echo "Test DICOM:   $TEST_DICOM"
echo "File size:    $(du -h "$TEST_DICOM" | cut -f1)"
echo "=========================================="
echo

# Generate random boundary
BOUNDARY="DICOMweb_Boundary_$(date +%s)_$$"

# Create multipart request body
TEMP_REQUEST=$(mktemp)
trap "rm -f $TEMP_REQUEST" EXIT

{
    echo "--${BOUNDARY}"
    echo "Content-Type: application/dicom"
    echo
    cat "$TEST_DICOM"
    echo
    echo "--${BOUNDARY}--"
} > "$TEMP_REQUEST"

echo "Request body size: $(du -h "$TEMP_REQUEST" | cut -f1)"
echo "Boundary: $BOUNDARY"
echo

# Upload via STOW-RS
echo "Uploading DICOM instance via STOW-RS..."
echo

response=$(curl -v -u "${USERNAME}:${PASSWORD}" \
    -H "Content-Type: multipart/related; boundary=${BOUNDARY}" \
    --data-binary "@${TEMP_REQUEST}" \
    "${STOW_ENDPOINT}" 2>&1)

echo "$response"
echo
echo "=========================================="

# Check response
if echo "$response" | grep -q "HTTP/.*200"; then
    echo "✅ STOW-RS upload successful (HTTP 200)"

    # Extract response body (after blank line following headers)
    response_body=$(echo "$response" | sed -n '/^{/,/^}/p' | head -1)

    if [ -n "$response_body" ]; then
        echo "Response body:"
        echo "$response_body" | python3 -m json.tool 2>/dev/null || echo "$response_body"
    fi

    echo
    echo "Next steps:"
    echo "1. Check XNAT prearchive: ${XNAT_URL}/app/template/XDATScreen_prearchive.vm"
    echo "2. Query via QIDO-RS: ${XNAT_URL}/xapi/dicomweb/projects/${PROJECT_ID}/studies"
    exit 0
elif echo "$response" | grep -q "HTTP/.*401"; then
    echo "❌ STOW-RS upload failed: Authentication required (HTTP 401)"
    echo "Check username/password"
    exit 1
elif echo "$response" | grep -q "HTTP/.*403"; then
    echo "❌ STOW-RS upload failed: Forbidden (HTTP 403)"
    echo "Check project access permissions"
    exit 1
elif echo "$response" | grep -q "HTTP/.*404"; then
    echo "❌ STOW-RS upload failed: Not found (HTTP 404)"
    echo "Check that the plugin is installed and XNAT is running"
    exit 1
else
    echo "❌ STOW-RS upload failed"
    echo "Check the response above for details"
    exit 1
fi
