# STOW-RS Deployment Success

> **Historical Note**: This document describes the initial STOW-RS implementation (November 2025). The current implementation has been enhanced with:
> - Strategy pattern (GradualDicomImporter and DirectWrite strategies)
> - Automatic session registration via PrearcDatabase API
> - Project validation in StowRsApi
> - Improved multipart parsing with Mime4jHybridParser
>
> See README.md and `dicomweb-doc/` for current implementation details.

**Date**: 2025-11-18
**Status**: ✅ **WORKING - First successful STOW-RS upload!**

## What Works

✅ **STOW-RS Upload - FULLY FUNCTIONAL**
- Endpoint: `POST /xapi/dicomweb/projects/{projectId}/studies`
- Multipart/related parsing: Working
- DICOM extraction: Working
- File writing to prearchive: Working
- HTTP 200 response with success count

✅ **Patched XNAT Deployment**
- xnat-web-1.9.2.1.war with multipart/related exclusion fix
- Deployed via mounted volume (`./xnat/webapp`)
- MultipartRelatedHttpMessageConverter registered
- multipart/related requests bypass Spring's resolver

✅ **Infrastructure**
- docker-compose.yml configured with webapp mount
- Deployment scripts created and tested
- Test scripts functional

## Test Result

```bash
./test_stow_rs.sh http://your-xnat-server YOUR_PROJECT your_username your_password
```

**Response**:
```
HTTP/1.1 200 OK
Content-Type: application/json

{"success": 1, "failed": 0, "message": "Stored 1 instances successfully"}
```

**Files Written**:
- Location: `/data/prearchive/DICOMWEB_TEST/{timestamp}/DICOMWEB_{id}/SCANS/{seriesNumber}/`
- DICOM files written with SOP Instance UID names
- `.prearchive` marker file created

## Architecture

### Current Implementation

```
HTTP POST multipart/related
    ↓
StowRsApi.storeInstances()
    ├─ Parse multipart (handles CRLF and LF line endings)
    ├─ Extract DICOM data
    ├─ Validate DICM marker
    └─ Call XnatDicomServiceImpl.storeInstances()
        ├─ Verify project access
        ├─ Create prearchive directory structure
        ├─ Write DICOM files (seriesNumber/sopInstanceUID.dcm)
        ├─ Create .prearchive marker
        └─ Return success/fail counts
```

### Key Components

**StowRsApi.java** - REST endpoint
- Reads multipart/related directly from HttpServletRequest
- Parses boundary-separated parts
- Extracts DICOM data (validates DICM marker)
- Returns simplified JSON response

**XnatDicomServiceImpl.java** - Service layer
- Project access validation
- Prearchive path calculation
- DICOM file writing
- Directory structure creation

**InputStreamFileWriterWrapper.java** - Utility
- Wraps InputStream for FileWriterWrapperI interface
- Currently unused (for future ImporterHandlerA integration)

## Configuration Required

### 1. Patched XNAT WAR

**File**: `xnat-web/src/main/java/org/nrg/xnat/configuration/WebConfig.java`

```java
@Bean
public MultipartResolver multipartResolver() {
    final StandardServletMultipartResolver delegate = new StandardServletMultipartResolver();

    return new MultipartResolver() {
        @Override
        public boolean isMultipart(HttpServletRequest request) {
            String contentType = request.getContentType();
            if (contentType != null && contentType.toLowerCase().contains("multipart/related")) {
                return false; // Skip multipart/related - let DICOMweb handle it
            }
            return delegate.isMultipart(request);
        }
        // ... delegate other methods
    };
}
```

### 2. Docker Compose Volume

**File**: `docker-compose.yml`

```yaml
volumes:
  - ./xnat/webapp:/usr/local/tomcat/webapps/ROOT
  - ./xnat/plugins:${XNAT_HOME}/plugins
```

## Deployment Instructions

### Build Plugin

```bash
cd xnat_dicomweb_plugin
./gradlew clean jar
```

### Deploy Patched XNAT (One-time)

```bash
cd xnat_docker_testing
./deploy_patched_war.sh
```

### Deploy Plugin

```bash
cd xnat_docker_testing
cp ../xnat_dicomweb_plugin/build/libs/xnat-dicomweb-plugin-1.1.3.jar ./xnat/plugins/
docker-compose restart xnat-web
```

### Test Upload

```bash
cd xnat_dicomweb_plugin
./test_stow_rs.sh http://localhost DICOMWEB_TEST admin admin
```

## Known Issues & Limitations

### javax.json Dependency (RESOLVED)

**Problem**: DicomWebUtils used javax.json (not available in XNAT runtime)
**Solution**: Removed javax.json, implemented simplified JSON response
**Impact**:
- ✅ STOW-RS: Works with simplified response
- ❌ QIDO-RS: toJson() not implemented (returns UnsupportedOperationException)
- ❌ WADO-RS: toJson() not implemented (returns UnsupportedOperationException)

**TODO**: Implement DICOM-to-JSON conversion using Jackson (available in XNAT)

### Prearchive Integration (IN PROGRESS)

**Current**: Manual file writing to prearchive directories
**TODO**: Use proper XNAT pattern:
- Create PrearcSession
- Use PrearcArchiveService.archiveWithLockAndSync()
- Integrate with archival workflow
- Support automatic archival after upload

See user examples:
- `OptimizedPrearchiveArchiveHandler.java`
- `PrearcArchiveService.java`

### Response Format (SIMPLIFIED)

**Current**: `{"success": 1, "failed": 0, "message": "..."}`
**TODO**: Implement proper DICOM PS3.18 STOW-RS response with:
- Retrieved URL (Tag 00081190)
- Referenced SOP Sequence (Tag 00081199)
- Failed SOP Sequence (Tag 00081198)
- Proper DICOM JSON format

## Next Steps

### Immediate (Working STOW-RS)

1. ✅ STOW-RS uploads working
2. ⏳ Verify files in prearchive UI
3. ⏳ Test manual archival from prearchive
4. ⏳ Verify QIDO-RS can retrieve uploaded studies

### Short-term (Proper Integration)

1. Refactor to use PrearcSession pattern
2. Implement Jackson-based DICOM JSON conversion
3. Fix QIDO-RS and WADO-RS endpoints
4. Implement proper STOW-RS response format
5. Add comprehensive error handling

### Long-term (Production Ready)

1. Caching for metadata/file paths
2. Query parameter support (PatientName, StudyDate filters)
3. Pagination
4. Multi-frame DICOM support
5. Performance optimization
6. Apps Menu integration
7. Create TESTING_CHECKLIST.md
8. Create PERMISSIONS.md (if needed)

## Files Modified in This Session

### Plugin Files

- `src/main/java/org/nrg/xnat/dicomweb/service/XnatDicomServiceImpl.java`
  - Refactored storeInstances() to write to prearchive
  - Removed ImporterHandlerA approach
  - Added debug logging

- `src/main/java/org/nrg/xnat/dicomweb/rest/StowRsApi.java`
  - Fixed multipart parsing (CRLF and LF support)
  - Simplified JSON response
  - Enhanced debug output

- `src/main/java/org/nrg/xnat/dicomweb/utils/DicomWebUtils.java`
  - Removed javax.json imports
  - toJson() returns UnsupportedOperationException
  - TODO: Implement with Jackson

- `src/main/java/org/nrg/xnat/dicomweb/util/InputStreamFileWriterWrapper.java` (NEW)
  - Created for future PrearcSession integration

### Docker/Deployment Files

- `xnat_docker_testing/docker-compose.yml`
  - Added webapp volume mount

- `xnat_docker_testing/deploy_patched_war.sh` (NEW)
  - Deploys patched xnat-web WAR

- `xnat_docker_testing/deploy_dicomweb.sh` (NEW)
  - Deploys DICOMweb plugin

- `xnat_dicomweb_plugin/test_stow_rs.sh` (NEW)
  - Tests STOW-RS upload

### Documentation

- `PROMPTS.md` - Updated with session details
- `DEPLOYMENT_SUCCESS.md` (this file)
- `/Users/james/.claude/CLAUDE.md` - Added localhost deployment section

## References

### XNAT JSON Patterns (from agent analysis)

- Primary: Jackson (Fasterxml) - ObjectMapper
- SerializerService: `_serializer.toJson(object)`
- Legacy: XFTTable.toJSON()
- Restlet: org.json.JSONObject

### Prearchive Patterns (from user examples)

- PrearcSession creation
- PrearcArchiveService.archiveWithLockAndSync()
- PrearcDatabase.setStatus() management
- Status flow: RECEIVING → ARCHIVING → _ARCHIVING → _DELETING
- OptimizedPrearcSessionArchiver for actual archival

### DICOMweb Standards

- DICOM PS3.18 - Web Services
- STOW-RS: Section 10.5
- QIDO-RS: Section 10
- WADO-RS: Section 9

## Verification

```bash
# Check plugin loaded
docker logs xnat-web 2>&1 | grep -i dicomweb

# Should see:
# === DicomWebConfig: Registered MultipartRelatedHttpMessageConverter at index 0

# Check prearchive
docker exec xnat-web ls -la /data/prearchive/DICOMWEB_TEST/

# Test upload
cd xnat_dicomweb_plugin
./test_stow_rs.sh http://localhost DICOMWEB_TEST admin admin

# Should return:
# HTTP/1.1 200 OK
# {"success": 1, "failed": 0, "message": "Stored 1 instances successfully"}
```

## Success Metrics

✅ **STOW-RS Upload**: Working
✅ **Multipart Parsing**: Working
✅ **DICOM Extraction**: Working
✅ **File Writing**: Working
✅ **Patched XNAT**: Deployed
✅ **Test Scripts**: Created
⏳ **Prearchive UI**: To be verified
❌ **QIDO-RS**: Needs Jackson JSON
❌ **WADO-RS**: Needs Jackson JSON

## Conclusion

**STOW-RS is now functional** for uploading DICOM instances to XNAT via DICOMweb protocol!

The implementation successfully:
1. Parses multipart/related requests
2. Extracts and validates DICOM data
3. Writes files to prearchive with proper structure
4. Returns HTTP 200 with success status

Next priority is integrating with XNAT's archival workflow using PrearcSession pattern and implementing Jackson-based DICOM JSON for QIDO/WADO endpoints.
