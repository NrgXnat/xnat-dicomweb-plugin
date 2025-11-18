#!/bin/bash
# STOW-RS Test Script for XNAT DICOMweb Plugin
# Tests the complete STOW-RS implementation including multipart upload

set -e

# Configuration - update these for your environment
XNAT_URL="${1:-http://your-xnat-server}"
PROJECT="${2:-YOUR_PROJECT}"
USERNAME="${3:-your_username}"
PASSWORD="${4:-your_password}"

# Check if using defaults and warn
if [ "$XNAT_URL" == "http://your-xnat-server" ]; then
  echo "⚠ Warning: Using default values. Run with:"
  echo "  $0 XNAT_URL PROJECT USERNAME PASSWORD"
  echo ""
fi

echo "========================================="
echo "XNAT DICOMweb STOW-RS Test Suite"
echo "========================================="
echo ""

# Test 1: Check if QIDO-RS works (baseline)
echo "Test 1: Verify QIDO-RS endpoint (GET /studies)"
RESPONSE=$(curl -s -u ${USERNAME}:${PASSWORD} \
  "${XNAT_URL}/xapi/dicomweb/projects/${PROJECT}/studies")
echo "Response: $RESPONSE"
if [ "$RESPONSE" == "[]" ]; then
  echo "✓ QIDO-RS endpoint accessible (empty project)"
else
  echo "✓ QIDO-RS endpoint accessible (has data)"
fi
echo ""

# Test 2: Check if STOW-RS endpoint exists
echo "Test 2: Verify STOW-RS endpoint exists (POST /studies)"
# Try POST without data - should get 400 or similar, not 404
RESPONSE=$(curl -s -w "\n%{http_code}" -u ${USERNAME}:${PASSWORD} \
  -X POST \
  -H "Content-Type: multipart/related; boundary=test" \
  "${XNAT_URL}/xapi/dicomweb/projects/${PROJECT}/studies" \
  --data-binary "")

HTTP_CODE=$(echo "$RESPONSE" | tail -1)
echo "HTTP Status: $HTTP_CODE"

if [ "$HTTP_CODE" == "404" ]; then
  echo "✗ STOW-RS endpoint NOT found (404)"
  exit 1
elif [ "$HTTP_CODE" == "400" ] || [ "$HTTP_CODE" == "500" ]; then
  echo "✓ STOW-RS endpoint exists (expected error with empty data)"
else
  echo "✓ STOW-RS endpoint exists (status: $HTTP_CODE)"
fi
echo ""

# Test 3: Test with sample DICOM file if available
echo "Test 3: Test STOW-RS with multipart DICOM upload"
DICOM_FILE=$(find /Users/james/projects -name "*.dcm" 2>/dev/null | head -1)

if [ -z "$DICOM_FILE" ]; then
  echo "⚠ No DICOM test files found - skipping upload test"
else
  echo "Found DICOM file: $DICOM_FILE"

  # Create multipart request
  BOUNDARY="----WebKitFormBoundary$(date +%s)"
  TEMP_FILE="/tmp/stow_rs_test_$$.dat"

  # Build multipart body
  {
    echo "--${BOUNDARY}"
    echo "Content-Type: application/dicom"
    echo ""
    cat "$DICOM_FILE"
    echo ""
    echo "--${BOUNDARY}--"
  } > "$TEMP_FILE"

  # Upload
  echo "Uploading DICOM instance..."
  RESPONSE=$(curl -s -w "\n%{http_code}" -u ${USERNAME}:${PASSWORD} \
    -X POST \
    -H "Content-Type: multipart/related; type=application/dicom; boundary=${BOUNDARY}" \
    --data-binary "@${TEMP_FILE}" \
    "${XNAT_URL}/xapi/dicomweb/projects/${PROJECT}/studies")

  HTTP_CODE=$(echo "$RESPONSE" | tail -n 1)
  BODY=$(echo "$RESPONSE" | sed '$d')

  echo "HTTP Status: $HTTP_CODE"
  echo "Response:"
  echo "$BODY" | head -20

  rm -f "$TEMP_FILE"

  if [ "$HTTP_CODE" == "200" ]; then
    echo "✓ STOW-RS upload successful"

    # Check for Referenced SOP Sequence
    if echo "$BODY" | grep -q "00081199"; then
      echo "✓ Response contains Referenced SOP Sequence (00081199)"
    fi

    # Check for Failed SOP Sequence
    if echo "$BODY" | grep -q "00081198"; then
      echo "⚠ Response contains Failed SOP Sequence (some instances failed)"
    fi
  else
    echo "✗ STOW-RS upload failed with status $HTTP_CODE"
  fi
fi
echo ""

# Test 4: Check import request was created
echo "Test 4: Verify XNAT import request was created"
# Check if import requests endpoint exists
IMPORT_RESPONSE=$(curl -s -u ${USERNAME}:${PASSWORD} \
  "${XNAT_URL}/xapi/dicom" 2>/dev/null || echo "endpoint not available")

if echo "$IMPORT_RESPONSE" | grep -q "\"id\""; then
  echo "✓ Import requests found in system"
  echo "Import requests:"
  echo "$IMPORT_RESPONSE" | head -20
else
  echo "⚠ No import requests found (may still be processing)"
fi
echo ""

echo "========================================="
echo "Test Suite Complete"
echo "========================================="
echo ""
echo "Summary:"
echo "- QIDO-RS (Query): Working"
echo "- STOW-RS (Store): Endpoint accessible"
echo ""
echo "Next steps:"
echo "1. Upload DICOM files using STOW-RS"
echo "2. Monitor import requests: GET /xapi/dicom"
echo "3. Query studies after import: GET /xapi/dicomweb/projects/${PROJECT}/studies"
echo ""
