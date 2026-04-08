# Study Metadata Endpoint Fix

## Problem

The study metadata endpoint (`/studies/{uid}/metadata`) was incorrectly calling `searchInstances()` with `null` for the `seriesInstanceUID` parameter. This caused a `NullPointerException` because `searchInstances()` performs `seriesInstanceUID.equals(scan.getUid())` when iterating through scans.

**Impact:**
- Every study metadata request returned 404
- NullPointerException logged for each request
- Endpoint was completely unusable

## Root Cause

The initial fix attempted to use `searchInstances(user, projectId, studyUID, null, null)` to retrieve all instance metadata for a study. However, `searchInstances()` requires a non-null `seriesInstanceUID` parameter and immediately throws NPE when it's null.

## Solution

Created a new service method `retrieveAllStudyInstanceMetadata()` that:
1. Finds the session by StudyInstanceUID
2. Iterates through all scans (series) in the session
3. Collects instance metadata from each scan using `readDicomFilesFromScan()`
4. Returns a complete list of all instance metadata

**Files Changed:**
- `XnatDicomService.java` - Added interface method
- `XnatDicomServiceImpl.java` - Implemented new method
- `WadoRsApi.java` - Updated endpoint to use new method

## DICOMweb Compliance

Per DICOM PS3.18, the `/studies/{uid}/metadata` endpoint should return metadata for **all instances** in the study, not just study-level attributes. This fix ensures compliance with the DICOMweb standard.

**Response Format:**
```json
[
  {
    "00080016": {"vr": "UI", "Value": ["1.2.840.10008.5.1.4.1.1.2"]},
    "00080018": {"vr": "UI", "Value": ["1.2.3.4.5.6.1"]},
    ...
  },
  {
    "00080016": {"vr": "UI", "Value": ["1.2.840.10008.5.1.4.1.1.2"]},
    "00080018": {"vr": "UI", "Value": ["1.2.3.4.5.6.2"]},
    ...
  }
]
```

Each array element contains the complete DICOM metadata for one instance, including:
- SOP Class UID (00080016)
- SOP Instance UID (00080018)
- Study/Series/Instance UIDs
- All other DICOM attributes

## Testing

### Unit Tests
Added `WadoRsApiTest.java` with 4 tests:
- ✅ Returns array of all instances
- ✅ Returns 404 for missing studies
- ✅ Each instance has required DICOM tags
- ✅ No NullPointerException regression test

### E2E Tests
Added `DicomWebPluginE2ETest.java` with 6 tests:
- ✅ QIDO-RS study search
- ✅ WADO-RS study metadata returns all instances
- ✅ No NullPointerException regression
- ✅ 404 for non-existent studies
- ✅ Series search
- ✅ Study metadata includes all series instances

### Manual Testing
- Deployed to localhost and demo02
- Verified no NullPointerException in logs
- Confirmed endpoint responds correctly (200 for valid studies, 404 for missing)

## Deployment

**Build:**
```bash
./gradlew clean build -x test
```

**Deploy to XNAT:**
```bash
docker cp build/libs/xnat-dicomweb-plugin-1.1.2.jar xnat-web:/data/xnat/home/plugins/
docker restart xnat-web
```

**Verify:**
```bash
# Wait for XNAT to start
docker logs xnat-web 2>&1 | grep "Server startup"

# Test endpoint
curl -u admin:admin "http://localhost/xapi/dicomweb/projects/{PROJECT_ID}/studies/{STUDY_UID}/metadata" \
  -H "Accept: application/dicom+json"
```

## Related Endpoints

**Study Retrieval (DICOM instances):**
- `GET /studies/{uid}` - Returns all DICOM files as multipart

**Study Metadata:**
- `GET /studies/{uid}/metadata` - Returns all instance metadata as JSON (this fix)

The fix ensures these endpoints work correctly and don't conflict with each other due to proper routing order in `WadoRsApi.java`.
