# STOW-RS Implementation Context Handoff

**Date:** November 17, 2025
**Task:** Implement STOW-RS (DICOMweb storage) for XNAT DICOMweb Plugin
**Status:** 98% Complete - Multipart parsing needs debugging
**PR:** https://github.com/mrjamesdickson/xnat_dicomweb_proxy/pull/19

---

## Current Status

### ✅ What's Complete:

1. **Full STOW-RS Implementation**
   - REST endpoint: `POST /xapi/dicomweb/projects/{projectId}/studies`
   - Service layer: `XnatDicomServiceImpl.storeInstances()`
   - Response models: `StowRsResponse`, `InstanceStatus`
   - DICOM PS3.18 compliant response format
   - All code compiles and builds successfully

2. **XNAT Integration**
   - Uses `GradualDicomImporter` for direct XNAT import
   - Proper `FileWriterWrapperI` implementation
   - Auto-archive support (skips prearchive)
   - Security checks and permission validation
   - Temp directory cleanup

3. **Testing & Documentation**
   - 37 unit tests passing
   - Test scripts created: `/tmp/test_stow_simple.sh`, `/tmp/test_stow_direct.sh`
   - Implementation plan: `docs/STOW_RS_IMPLEMENTATION_PLAN.md`
   - Updated README.md

4. **Build Status**
   - Plugin builds successfully: `build/libs/xnat-dicomweb-proxy-1.1.3.jar` (50KB)
   - Deployed to localhost XNAT: `xnat-docker-compose-xnat-web-1`
   - QIDO-RS and WADO-RS endpoints working

### ⚠️ Current Issue:

**Multipart Parser Not Working**
- Symptom: Returns `{"error": "No DICOM instances in request"}`
- Endpoint is accessible (not 404)
- Request body is received (35KB+ uploaded successfully)
- Parser isn't extracting DICOM instances from multipart/related body
- Logs not appearing in application logs (may be log level issue)

---

## File Locations

### Key Source Files:
```
/Users/james/projects/xnat_dicomweb_plugin/
├── src/main/java/org/nrg/xnat/dicomweb/
│   ├── rest/StowRsApi.java                    # REST controller (365 lines)
│   ├── service/XnatDicomService.java           # Interface with models
│   └── service/XnatDicomServiceImpl.java       # Implementation with GradualDicomImporter
├── src/test/java/org/nrg/xnat/dicomweb/rest/
│   └── StowRsApiTest.java                      # Unit tests (155 lines)
├── docs/
│   └── STOW_RS_IMPLEMENTATION_PLAN.md          # Complete design doc (600+ lines)
├── test/
│   └── test_stow_rs.sh                         # Integration test script
└── build/libs/
    └── xnat-dicomweb-proxy-1.1.3.jar          # Built plugin (50KB)
```

### XNAT Deployment:
```
Container: xnat-docker-compose-xnat-web-1
Plugin: /data/xnat/home/plugins/xnat-dicomweb-proxy-1.1.3.jar
Logs: /Users/james/projects/xnat_docker_testing/xnat-data/home/logs/
  - application.log (STOW-RS logs should be here)
  - xapi.log
  - spring.log
```

### Test Files:
```
/tmp/test_stow_simple.sh          # Test with large DICOM file
/tmp/test_stow_direct.sh          # Test with project sample.dcm
/Users/james/projects/xnat_dicomweb_plugin/src/test/resources/test-data/sample.dcm
```

---

## Key Code Sections

### Multipart Parser (StowRsApi.java:136-235)
```java
private List<InputStream> parseMultipartRequest(HttpServletRequest request) {
    // Current approach:
    // 1. Read entire body into ByteArrayOutputStream
    // 2. Convert to String with ISO-8859-1 encoding
    // 3. Split by boundary using regex
    // 4. Extract parts with "application/dicom" header
    // 5. Return as InputStreams

    // Issue: Not finding DICOM parts - needs debugging
}
```

### XNAT Import (XnatDicomServiceImpl.java:1482-1550)
```java
private void triggerXnatImport(UserI user, String projectId, File tempDir) {
    // Uses GradualDicomImporter per file
    // Creates FileWriterWrapperI implementation
    // Calls importer.call() synchronously
    // Cleans up temp directory
}
```

---

## Debugging Steps Needed

### 1. Check if Logs Are Being Written
```bash
# Check if STOW-RS logs appear
tail -f /Users/james/projects/xnat_docker_testing/xnat-data/home/logs/application.log | grep "STOW-RS"

# Or check all recent logs
tail -200 /Users/james/projects/xnat_docker_testing/xnat-data/home/logs/application.log
```

### 2. Verify Plugin Loaded
```bash
# Check plugin file exists
docker exec xnat-docker-compose-xnat-web-1 ls -lh /data/xnat/home/plugins/ | grep dicomweb

# Check for startup errors
tail -100 /Users/james/projects/xnat_docker_testing/xnat-data/home/logs/spring.log
```

### 3. Test Endpoint
```bash
# Test QIDO-RS (should work)
curl -s -u admin:admin http://localhost/xapi/dicomweb/projects/test/studies

# Test STOW-RS
/tmp/test_stow_direct.sh
```

---

## Problem Analysis

### Current Parser Logic:
```java
// Extract boundary from Content-Type
String boundary = extractBoundary(contentType); // e.g., "DicomBoundary123"

// Read body
byte[] fullData = buffer.toByteArray();

// Convert to string
String multipartBody = new String(fullData, "ISO-8859-1");

// Split by "--boundary"
String boundaryMarker = "--" + boundary;
String[] parts = multipartBody.split(Pattern.quote(boundaryMarker));

// For each part, look for "application/dicom" header
```

### Possible Issues:
1. **Boundary mismatch** - Content-Type boundary vs actual boundary in body
2. **Encoding problem** - ISO-8859-1 may corrupt binary DICOM data
3. **Header parsing** - May not finding `\r\n\r\n` separator correctly
4. **Logging disabled** - Can't see what's actually happening

---

## Solutions to Try

### Option 1: Enhanced Logging
Add more aggressive logging to see actual data:
```java
// In parseMultipartRequest()
System.out.println("STOW-RS DEBUG: boundary=" + boundary);
System.out.println("STOW-RS DEBUG: data length=" + fullData.length);
System.out.println("STOW-RS DEBUG: parts.length=" + parts.length);

// Check catalina.out or stdout
docker logs xnat-docker-compose-xnat-web-1 | grep "STOW-RS DEBUG"
```

### Option 2: Use Apache Commons FileUpload
Replace custom parser with proven library:
```gradle
// build.gradle
compileOnly 'commons-fileupload:commons-fileupload:1.4'
```

```java
import org.apache.commons.fileupload.FileItemIterator;
import org.apache.commons.fileupload.FileItemStream;
import org.apache.commons.fileupload.servlet.ServletFileUpload;

ServletFileUpload upload = new ServletFileUpload();
FileItemIterator iterator = upload.getItemIterator(request);
while (iterator.hasNext()) {
    FileItemStream item = iterator.next();
    if (!item.isFormField() && item.getContentType().contains("dicom")) {
        streams.add(item.openStream());
    }
}
```

### Option 3: Debug Endpoint
Create simple endpoint to echo request details:
```java
@XapiRequestMapping(value = "/dicomweb/debug", method = POST)
public ResponseEntity<String> debug(HttpServletRequest request) {
    String contentType = request.getContentType();
    int contentLength = request.getContentLength();
    String boundary = extractBoundary(contentType);

    return ResponseEntity.ok(String.format(
        "ContentType: %s\nLength: %d\nBoundary: %s",
        contentType, contentLength, boundary));
}
```

---

## Quick Commands Reference

### Build & Deploy:
```bash
cd /Users/james/projects/xnat_dicomweb_plugin

# Build
./gradlew jar

# Deploy
docker cp build/libs/xnat-dicomweb-proxy-1.1.3.jar xnat-docker-compose-xnat-web-1:/data/xnat/home/plugins/

# Restart
docker restart xnat-docker-compose-xnat-web-1

# Wait for startup
sleep 45
```

### Test:
```bash
# Simple test
/tmp/test_stow_direct.sh

# Check logs
tail -100 /Users/james/projects/xnat_docker_testing/xnat-data/home/logs/application.log | grep -i stow
```

### Git:
```bash
cd /Users/james/projects/xnat_dicomweb_plugin

# Current branch
git branch  # feature/stow-rs-implementation

# Status
git status

# Commit
git add -A
git commit -m "Your message"
git push
```

---

## Test Data

### Sample DICOM File:
```
/Users/james/projects/xnat_dicomweb_plugin/src/test/resources/test-data/sample.dcm
Size: 35,178 bytes
```

### Multipart Format Expected:
```
--DicomBoundary123\r\n
Content-Type: application/dicom\r\n
\r\n
[DICOM binary data]
\r\n
--DicomBoundary123--\r\n
```

---

## Next Steps

1. **Immediate:** Add System.out.println() debug logging to see actual data
2. **Check:** Docker logs for debug output
3. **If logs show data:** Fix parser logic based on actual format
4. **If no logs:** Check plugin loaded correctly
5. **Alternative:** Implement Apache Commons FileUpload

---

## Environment

- **XNAT:** localhost via Docker (xnat-docker-compose-xnat-web-1)
- **Credentials:** admin/admin
- **Test Project:** test
- **XNAT Version:** 1.9.x
- **Java:** 8
- **Gradle:** 5.6.x

---

## Success Criteria

When working, should see:
```bash
$ /tmp/test_stow_direct.sh
Testing STOW-RS with: /path/to/sample.dcm
File size: 35178 bytes
Uploading...
{
  "00081190": {"vr": "UR", "Value": ["http://localhost/xapi/dicomweb/projects/test/studies/..."]},
  "00081199": {"vr": "SQ", "Value": [{"00081150": ..., "00081155": ...}]},
  "00081198": {"vr": "SQ", "Value": []}
}
```

And in XNAT:
```bash
$ curl -u admin:admin http://localhost/xapi/dicomweb/projects/test/studies
[{"0020000D": {...}, ...}]  # Shows imported study
```

---

## Additional Context

- User prefers GradualDicomImporter over DicomInboxImportRequestService
- All code must follow XNAT plugin standards (see ~/.claude/CLAUDE.md)
- Tests required for all code (currently have 37 passing unit tests)
- Plugin completes DICOMweb triumvirate: QIDO-RS, WADO-RS, STOW-RS

**PR Link:** https://github.com/mrjamesdickson/xnat_dicomweb_proxy/pull/19

---

End of context handoff. Good luck! 🚀
