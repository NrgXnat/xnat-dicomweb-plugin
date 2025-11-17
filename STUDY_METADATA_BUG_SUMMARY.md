# Study Metadata Endpoint 404 Bug - Investigation Summary

## Problem
The endpoint `/dicomweb/projects/{projectId}/studies/{studyUID}/metadata` returns 404.
All other WADO-RS endpoints work correctly.

## Expected Behavior
Should return JSON array of instance metadata (44 objects for test study).

## Current Status
- ✅ Series metadata works: `/studies/{studyUID}/series/{seriesUID}/metadata` → 200 OK
- ✅ Instance metadata works: `/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/metadata` → 200 OK  
- ✅ Study retrieval works: `/studies/{studyUID}` → 200 OK (multipart)
- ❌ Study metadata fails: `/studies/{studyUID}/metadata` → 404 NOT FOUND

## Technical Details
- **XNAT Version**: 1.9.0
- **Spring Framework**: 5.3.31 
- **Issue**: Path matching ambiguity between `/studies/{studyUID}` and `/studies/{studyUID}/metadata`

## What We've Tried

### 1. Method Reordering ❌
Moved `retrieveStudyMetadata()` before `retrieveStudy()` in source file.
**Result**: No effect. Spring MVC doesn't respect source file method order.

### 2. Different `produces` Values ❌
- `retrieveStudyMetadata`: `produces = "application/dicom+json"`
- `retrieveStudy`: `produces = "multipart/related"`
**Result**: No effect. Content negotiation alone doesn't disambiguate paths.

### 3. Combined Method with HttpServletRequest ❌
Single method handling both paths, checking `request.getRequestURI()`.
**Result**: Method never called - endpoint not registered at all.

### 4. Separate Methods with Logging ❌  
Two separate methods with extensive logging added.
**Result**: Logs show method is NEVER called. 404 returned before reaching method.

### 5. @Order Annotation ❌ (Current)
```java
@Order(1)
public ResponseEntity<String> retrieveStudyMetadata(...)

@Order(2)  
public ResponseEntity<InputStreamResource> retrieveStudy(...)
```
**Result**: Still returns 404. Method not being called.

## Root Cause Analysis

Spring MVC 5.3 is treating "metadata" as a valid value for the `{studyUID}` path variable.
When a request comes in for `/studies/1.2.3.../metadata`, Spring's path matching sees two possibilities:
1. `/studies/{studyUID}/metadata` where studyUID="1.2.3.."
2. `/studies/{studyUID}` where studyUID="1.2.3../metadata"

For some reason, Spring is choosing option 2 or rejecting both, resulting in 404.

## Next Steps to Try

### Option A: Path Variable Regex Constraint
Tell Spring that `studyUID` cannot be "metadata":
```java
@XapiRequestMapping("/studies/{studyUID:^(?!metadata$).*}/metadata")
```

### Option B: Custom HandlerMapping  
Configure XNAT's WebMvcConfigurer to use more specific path matching.

### Option C: Restructure Paths (Last Resort)
Move metadata to different base path (breaks DICOMweb spec compliance).

## Test Case
```bash
# This works
curl http://demo02/xapi/dicomweb/projects/Prostate-AEC/studies/1.3.6.1.4.1.14519.5.2.1.192342080646731188113889763362087290669/series/1.3.6.1.4.1.14519.5.2.1.236063181032654095384481129454095462816/metadata

# This fails  
curl http://demo02/xapi/dicomweb/projects/Prostate-AEC/studies/1.3.6.1.4.1.14519.5.2.1.192342080646731188113889763362087290669/metadata
```

## Files Modified
- `src/main/java/org/nrg/xnat/dicomweb/rest/WadoRsApi.java` - Added @Order, logging
- `src/main/resources/META-INF/resources/dicomweb-test.html` - Added test button
- `deploy_and_restart_demo02.sh` - Uses redeploy_morpheus.sh, excludes datatype plugin

Date: 2025-11-10
