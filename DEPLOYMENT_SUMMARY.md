# DICOMweb Study Metadata Fix - Deployment Summary

## Issue
User reported 406 error when calling study metadata endpoint. Investigation showed XNAT was returning 1 study object instead of N instance objects.

## Root Causes

### 1. Incorrect Response Content (FIXED)
**Problem**: `/studies/{studyUID}/metadata` returned study-level metadata (1 object) instead of instance metadata (N objects)

**Solution**: Changed from `retrieveStudyMetadata()` to `searchInstances(user, projectId, studyUID, null, null)`
- File: `WadoRsApi.java:329-334`
- Now returns array of all instance metadata in the study
- Compliant with DICOM PS3.18 Section 6.5.6

### 2. Spring MVC Path Matching Issue (FIXED)
**Problem**: Endpoint returned 404 because method order caused path matching conflict

**Solution**: Reordered methods so more specific path comes first
- Series metadata (longer path) now at line 263
- Study metadata (shorter path) now at line 312  
- Spring MVC registers specific paths before general paths

## Changes Made

### WadoRsApi.java
```java
// OLD ORDER (broken):
Line 280: retrieveStudyMetadata()    // /studies/{studyUID}/metadata
Line 328: retrieveSeriesMetadata()   // /studies/{studyUID}/series/{seriesUID}/metadata

// NEW ORDER (fixed):
Line 280: retrieveSeriesMetadata()   // /studies/{studyUID}/series/{seriesUID}/metadata  
Line 329: retrieveStudyMetadata()    // /studies/{studyUID}/metadata
```

### retrieveStudyMetadata() Implementation
```java
// OLD (incorrect):
Attributes attrs = dicomService.retrieveStudyMetadata(user, projectId, studyUID);
String json = "[" + DicomWebUtils.toJson(attrs) + "]";
// Returns: [{ study metadata }]

// NEW (correct):
List<Attributes> instances = dicomService.searchInstances(user, projectId, studyUID, null, null);
String json = "[" + instances.stream()
    .map(attrs -> DicomWebUtils.toJson(attrs))
    .collect(Collectors.joining(",")) + "]";
// Returns: [{instance1}, {instance2}, ..., {instanceN}]
```

## Testing

### Unit Tests
Created `WadoRsApiTest.java` with 3 tests:
- ✅ testRetrieveStudyMetadata_ReturnsArrayOfInstances
- ✅ testRetrieveStudyMetadata_NotFound  
- ✅ testRetrieveStudyMetadata_EachInstanceHasSOPInstanceUID

All tests pass.

### Expected Behavior
After deploying updated JAR (built Nov 10 09:51):

**Before**:
```json
[
  {
    "00080020": { "vr": "DA", "Value": ["19991130"] },
    "0020000D": { "vr": "UI", "Value": ["1.3.6..."] },
    "00201208": { "vr": "IS", "Value": ["44"] }
  }
]
```
- Array length: 1
- Content: Study metadata
- Missing: SOP Instance UIDs

**After**:
```json
[
  {
    "00080016": { "vr": "UI", "Value": ["1.2.840..."] },
    "00080018": { "vr": "UI", "Value": ["1.3.6...121"] },
    "0020000D": { "vr": "UI", "Value": ["1.3.6..."] },
    "0020000E": { "vr": "UI", "Value": ["1.3.6...893"] }
  },
  {
    "00080016": { "vr": "UI", "Value": ["1.2.840..."] },
    "00080018": { "vr": "UI", "Value": ["1.3.6...456"] },
    ...
  },
  ... // 42 more instances
]
```
- Array length: 44 (number of instances)
- Content: Instance metadata  
- Includes: SOP Instance UIDs (00080018), SOP Class UIDs (00080016)

## Deployment Instructions

1. Copy JAR to demo02:
   ```bash
   scp ~/projects/xnat_dicomweb_plugin/build/libs/xnat-dicomweb-proxy-1.1.1.jar \
       demo02:/home/james/xnat-docker-compose/xnat/plugins/
   ```

2. Restart XNAT:
   ```bash
   ssh demo02 "cd xnat-docker-compose && docker-compose restart xnat-web"
   ```

3. Wait 60 seconds for startup

4. Test endpoint:
   ```bash
   curl -u admin:admin \
     "http://demo02.xnatworks.io/xapi/dicomweb/projects/Prostate-AEC/studies/{studyUID}/metadata" \
     -H "Accept: application/dicom+json" | jq 'length'
   ```

   Should return number > 1 (e.g., 44)

## Files Changed
- `src/main/java/org/nrg/xnat/dicomweb/rest/WadoRsApi.java` - Fixed logic & reordered methods
- `src/test/java/org/nrg/xnat/dicomweb/rest/WadoRsApiTest.java` - New unit tests
- `test/COMPARISON_ANALYSIS.md` - Detailed before/after comparison
- `FIX_SUMMARY.md` - Initial fix documentation

## Build Info
- JAR: `xnat-dicomweb-proxy-1.1.1.jar`
- Built: Nov 10 2025, 09:51
- Size: 30KB
- Location: `~/projects/xnat_dicomweb_plugin/build/libs/`
