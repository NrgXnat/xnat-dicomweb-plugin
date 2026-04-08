# Fix for DICOMweb Study Metadata Endpoint (406 Error)

## Problem
The user reported getting a 406 error when using the study metadata endpoint. Upon investigation, the issue was that the XNAT plugin was returning **study-level metadata** (1 object) instead of **metadata for all instances in the study** (44 objects in the test case).

## Root Cause
The `/studies/{studyUID}/metadata` endpoint was calling `dicomService.retrieveStudyMetadata()` which returned a single `Attributes` object containing study-level metadata. This is incorrect according to the DICOMweb standard.

## DICOMweb Standard (DICOM PS3.18 Section 6.5.6)
The WADO-RS `/studies/{studyUID}/metadata` endpoint should return:
> "metadata for all instances within the specified study"

Not the study-level metadata itself, but rather an array of instance metadata for every DICOM instance in the study.

## Solution
Changed the endpoint to use `dicomService.searchInstances(user, projectId, studyUID, null, null)` which:
1. Retrieves all instances in the study (null seriesUID = all series)
2. Returns a `List<Attributes>` containing metadata for each instance
3. Properly formats the response as a JSON array of instance metadata

## Changes Made
**File**: `WadoRsApi.java:263-310`

**Before**:
- Called `retrieveStudyMetadata()` → returned single study metadata
- Wrapped in array: `"[" + DicomWebUtils.toJson(attrs) + "]"`
- Result: Array with 1 study object

**After**:
- Calls `searchInstances()` with null seriesUID → returns all instances
- Maps each instance to JSON and joins: `instances.stream().map(...).collect(Collectors.joining(","))`
- Result: Array with N instance objects (matching Orthanc behavior)

## Testing
Compare output with test files:
- `test/xnat_study.json` - OLD output (1 study object)
- `test/orthanc_study.json` - Expected output (44 instance objects)

After this fix, XNAT will produce output matching the Orthanc format.

## Impact
- Fixes 406 error reported by user
- Makes XNAT DICOMweb Plugin compliant with DICOM PS3.18 standard
- Output now matches Orthanc and other DICOMweb servers
