# STOW-RS Implementation Plan

## ✅ IMPLEMENTATION COMPLETE

This document outlines the implementation of STOW-RS (STore Over the Web by RESTful Services) support for the XNAT DICOMweb Proxy Plugin, completing the DICOMweb triumvirate (QIDO-RS, WADO-RS, STOW-RS).

**Status:** Fully implemented and tested
**Version:** 1.1.3
**Completion Date:** November 17, 2025

## Overview

This document describes the complete implementation of STOW-RS support, including integration with XNAT's native import pipeline using `DicomInboxImportRequestService`.

## DICOMweb STOW-RS Specification

### Reference
- DICOM PS3.18 Section 10.5: Store Transaction
- URL: https://dicom.nema.org/medical/dicom/current/output/html/part18.html#sect_10.5

### Key Requirements

#### 1. Endpoint Pattern
```
POST /dicomweb/projects/{projectId}/studies
```

**Content-Type:** `multipart/related; type="application/dicom"; boundary=<boundary>`

#### 2. Request Format
- HTTP POST with multipart/related body
- Each part contains one DICOM instance (PS3.10 binary format)
- Parts separated by boundary string
- Supports compressed and uncompressed transfer syntaxes

#### 3. Response Format
**Success (200 OK):**
```json
{
  "00081190": {
    "vr": "UR",
    "Value": ["http://xnat/xapi/dicomweb/projects/PROJECT/studies/{uid}"]
  },
  "00081198": {
    "vr": "SQ",
    "Value": [{
      "00081150": {"vr": "UI", "Value": ["1.2.840.10008.5.1.4.1.1.2"]},
      "00081155": {"vr": "UI", "Value": ["1.3.6.1.4.1.5962.1.1.0..."]},
      "00081190": {"vr": "UR", "Value": ["http://..."]}
    }]
  },
  "00081199": {
    "vr": "SQ",
    "Value": []
  }
}
```

**Tags:**
- `00081190` (Retrieve URL) - URL to retrieve stored study
- `00081198` (Failed SOP Sequence) - Instances that failed (empty if all succeed)
- `00081199` (Referenced SOP Sequence) - Successfully stored instances

**Failure Codes:**
- `400 Bad Request` - Invalid request format
- `401 Unauthorized` - Authentication required
- `403 Forbidden` - Insufficient permissions
- `409 Conflict` - Duplicate instances
- `507 Insufficient Storage` - Out of storage space

#### 4. Validation Requirements
- Verify each DICOM instance is valid (parseable)
- Check SOP Class UID and SOP Instance UID are present
- Check Study Instance UID matches (if multiple instances)
- Validate transfer syntax is supported
- Check project permissions (user must have edit access)

#### 5. Storage Behavior
- Store instances in XNAT archive
- Create sessions (studies) if they don't exist
- Create scans (series) if they don't exist
- Handle duplicate detection (same SOP Instance UID)
- Preserve original DICOM attributes

## Implementation Architecture

### Component Overview

```
┌─────────────────────────────────────────────┐
│          DICOMweb Client                     │
│      (OHIF, Weasis, Horos, etc.)            │
└──────────────────┬──────────────────────────┘
                   │ POST multipart/related
                   │
┌──────────────────▼──────────────────────────┐
│            StowRsApi.java                    │
│  - Parse multipart request                   │
│  - Extract DICOM instances                   │
│  - Validate user permissions                 │
│  - Call service layer                        │
│  - Build STOW-RS response                    │
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│       XnatDicomService.storeInstances()      │
│  - Validate DICOM instances                  │
│  - Extract metadata (Study/Series UIDs)      │
│  - Write files to temporary directory        │
│  - Call XNAT import pipeline                 │
│  - Return success/failure status             │
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│         XNAT Import Pipeline                 │
│  - GradualDicomImporter or                   │
│  - DicomInboxImporter                        │
│  - Creates sessions/scans                    │
│  - Archives DICOM files                      │
└──────────────────────────────────────────────┘
```

### Design Decisions

#### Approach 1: Direct XNAT Import (Recommended)
**Use XNAT's existing import services**

**Pros:**
- Leverages proven import logic
- Handles session/scan creation automatically
- Applies anonymization/de-identification if configured
- Respects project-level series import filters
- Integrates with prearchive/archive workflow
- Handles permissions correctly

**Cons:**
- More complex integration
- Need to write to temporary directory
- Async processing (may need to track import status)

**Implementation:**
```java
// 1. Write DICOM instances to temp directory
Path tempDir = Files.createTempDirectory("stow-rs-");

// 2. Save each instance as .dcm file
for (InputStream stream : dicomInstances) {
    Attributes attrs = readDicom(stream);
    String sopUID = attrs.getString(Tag.SOPInstanceUID);
    Path outFile = tempDir.resolve(sopUID + ".dcm");
    // Write to file
}

// 3. Trigger XNAT import
DicomInboxImportRequestService service = ...;
DicomInboxImportRequest request = new DicomInboxImportRequest(
    user, projectId, tempDir.toString(), params);
service.submit(request);

// 4. Monitor import status (optional)
// 5. Clean up temp directory after import completes
```

#### Approach 2: Direct File Writing (Not Recommended)
**Write directly to XNAT archive structure**

**Pros:**
- Faster (no temp files)
- Synchronous response

**Cons:**
- Bypasses import pipeline
- Must manually create sessions/scans
- Must manually update database
- No anonymization
- No series filtering
- Fragile (depends on archive structure)
- High risk of data corruption

**Decision:** Use Approach 1 (XNAT Import Pipeline)

## Implementation Details

### 1. Service Layer (XnatDicomServiceImpl.java)

```java
@Override
public StowRsResponse storeInstances(UserI user, String projectId,
                                      List<InputStream> dicomInstances) {
    List<InstanceStatus> statuses = new ArrayList<>();
    int successCount = 0;
    int failureCount = 0;

    try {
        // Verify project access
        XnatProjectdata project = XnatProjectdata.getXnatProjectdatasById(
            projectId, user, false);
        if (project == null) {
            throw new SecurityException("No access to project: " + projectId);
        }

        // Create temporary directory for upload
        Path tempDir = Files.createTempDirectory("stow-rs-");
        logger.info("Created temp directory for STOW-RS: {}", tempDir);

        // Process each instance
        for (InputStream stream : dicomInstances) {
            try {
                // Read DICOM attributes
                Attributes attrs = DicomWebUtils.readDicom(stream);
                String sopInstanceUID = attrs.getString(Tag.SOPInstanceUID);
                String sopClassUID = attrs.getString(Tag.SOPClassUID);

                if (sopInstanceUID == null || sopClassUID == null) {
                    statuses.add(new InstanceStatus(
                        sopInstanceUID, sopClassUID, false,
                        "Missing required UIDs", 0xA900)); // Dataset does not match SOP Class
                    failureCount++;
                    continue;
                }

                // Write to temp file
                Path outFile = tempDir.resolve(sopInstanceUID + ".dcm");
                try (FileOutputStream fos = new FileOutputStream(outFile.toFile());
                     DicomOutputStream dos = new DicomOutputStream(fos)) {
                    dos.writeDataset(null, attrs);
                }

                statuses.add(new InstanceStatus(
                    sopInstanceUID, sopClassUID, true, null, 0));
                successCount++;

            } catch (Exception e) {
                logger.error("Error processing DICOM instance", e);
                statuses.add(new InstanceStatus(
                    null, null, false, e.getMessage(), 0xC000)); // Error
                failureCount++;
            }
        }

        // Trigger XNAT import if any files succeeded
        if (successCount > 0) {
            importToXnat(user, projectId, tempDir);
        } else {
            // Clean up if all failed
            FileUtils.deleteDirectory(tempDir.toFile());
        }

    } catch (Exception e) {
        logger.error("STOW-RS storage failed", e);
        throw new RuntimeException("Storage failed: " + e.getMessage(), e);
    }

    return new StowRsResponse(successCount, failureCount, statuses);
}

private void importToXnat(UserI user, String projectId, Path tempDir) {
    // Option 1: Use DicomInboxImportRequestService (async)
    DicomInboxImportRequestService service =
        XDAT.getContextService().getBean(DicomInboxImportRequestService.class);

    Map<String, Object> params = new HashMap<>();
    params.put("PROJECT_ID", projectId);
    params.put("path", tempDir.toString());
    params.put("cleanupAfterImport", "true");

    DicomInboxImportRequest request = new DicomInboxImportRequest(
        user.getLogin(), projectId, tempDir.toString(), params);
    service.submit(request);

    // Option 2: Use GradualDicomImporter directly (sync)
    // More complex but provides immediate feedback
}
```

### 2. REST Controller (StowRsApi.java)

```java
@XapiRestController
@Api("DICOMweb STOW-RS API")
public class StowRsApi extends AbstractXapiRestController {

    private final XnatDicomService dicomService;

    @Autowired
    public StowRsApi(XnatDicomService dicomService,
                     UserManagementServiceI userManagementService,
                     RoleHolder roleHolder) {
        super(userManagementService, roleHolder);
        this.dicomService = dicomService;
    }

    /**
     * Store DICOM instances (STOW-RS)
     * POST /dicomweb/projects/{projectId}/studies
     */
    @XapiRequestMapping(
        value = "/dicomweb/projects/{projectId}/studies",
        method = RequestMethod.POST,
        consumes = "multipart/related",
        produces = "application/dicom+json",
        restrictTo = Edit
    )
    @ApiOperation(value = "Store DICOM instances (STOW-RS)",
                  response = String.class)
    @ApiResponses({
        @ApiResponse(code = 200, message = "Instances stored"),
        @ApiResponse(code = 400, message = "Invalid request"),
        @ApiResponse(code = 401, message = "Authentication required"),
        @ApiResponse(code = 403, message = "Insufficient permissions"),
        @ApiResponse(code = 500, message = "Server error")
    })
    public ResponseEntity<String> storeInstances(
            @PathVariable String projectId,
            HttpServletRequest request) {

        try {
            UserI user = getSessionUser();

            // Parse multipart request
            List<InputStream> instances = parseMultipartRequest(request);

            if (instances.isEmpty()) {
                return ResponseEntity.badRequest().body(
                    createErrorResponse("No DICOM instances in request"));
            }

            // Store instances
            StowRsResponse response = dicomService.storeInstances(
                user, projectId, instances);

            // Build STOW-RS response
            String jsonResponse = buildStowRsResponse(
                response, projectId, request);

            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                    DicomWebUtils.getDicomJsonContentType()))
                .body(jsonResponse);

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(createErrorResponse(e.getMessage()));
        } catch (Exception e) {
            logger.error("STOW-RS error", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse(e.getMessage()));
        }
    }

    private List<InputStream> parseMultipartRequest(
            HttpServletRequest request) throws IOException {

        List<InputStream> streams = new ArrayList<>();
        String contentType = request.getContentType();

        if (!contentType.startsWith("multipart/related")) {
            throw new IllegalArgumentException(
                "Content-Type must be multipart/related");
        }

        // Extract boundary
        String boundary = extractBoundary(contentType);

        // Parse multipart body
        ServletInputStream input = request.getInputStream();
        MultipartStream multipartStream = new MultipartStream(
            input, boundary.getBytes(), 8192, null);

        boolean nextPart = multipartStream.skipPreamble();
        while (nextPart) {
            String headers = multipartStream.readHeaders();

            // Check if part is DICOM (application/dicom)
            if (headers.toLowerCase().contains("application/dicom")) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                multipartStream.readBodyData(baos);
                streams.add(new ByteArrayInputStream(baos.toByteArray()));
            } else {
                multipartStream.discardBodyData();
            }

            nextPart = multipartStream.readBoundary();
        }

        return streams;
    }

    private String buildStowRsResponse(StowRsResponse response,
                                        String projectId,
                                        HttpServletRequest request) {
        Attributes attrs = new Attributes();

        // Build base URL for retrieval
        String baseUrl = request.getRequestURL().toString()
            .replace("/studies", "");

        // Add retrieve URL (00081190)
        if (response.getSuccessCount() > 0) {
            // Get first successful study UID
            String studyUID = getFirstStudyUID(response);
            if (studyUID != null) {
                attrs.setString(Tag.RetrieveURL, VR.UR,
                    baseUrl + "/studies/" + studyUID);
            }
        }

        // Add Referenced SOP Sequence (00081199) - successes
        Sequence successSeq = attrs.newSequence(
            Tag.ReferencedSOPSequence, response.getSuccessCount());
        for (InstanceStatus status : response.getInstanceStatuses()) {
            if (status.isSuccess()) {
                Attributes item = new Attributes();
                item.setString(Tag.ReferencedSOPClassUID, VR.UI,
                    status.getSopClassUID());
                item.setString(Tag.ReferencedSOPInstanceUID, VR.UI,
                    status.getSopInstanceUID());
                item.setString(Tag.RetrieveURL, VR.UR,
                    buildInstanceURL(baseUrl, status));
                successSeq.add(item);
            }
        }

        // Add Failed SOP Sequence (00081198) - failures
        Sequence failedSeq = attrs.newSequence(
            Tag.FailedSOPSequence, response.getFailureCount());
        for (InstanceStatus status : response.getInstanceStatuses()) {
            if (!status.isSuccess()) {
                Attributes item = new Attributes();
                if (status.getSopClassUID() != null) {
                    item.setString(Tag.ReferencedSOPClassUID, VR.UI,
                        status.getSopClassUID());
                }
                if (status.getSopInstanceUID() != null) {
                    item.setString(Tag.ReferencedSOPInstanceUID, VR.UI,
                        status.getSopInstanceUID());
                }
                item.setInt(Tag.FailureReason, VR.US, status.getWarningCode());
                failedSeq.add(item);
            }
        }

        return DicomWebUtils.toJson(attrs);
    }
}
```

### 3. Dependencies

Add Apache Commons FileUpload for multipart parsing:

```gradle
// build.gradle
compileOnly 'commons-fileupload:commons-fileupload:1.4'
```

### 4. Testing Strategy

#### Unit Tests (StowRsApiTest.java)
- Test multipart parsing
- Test response building
- Test error handling
- Test permission checking

#### Integration Tests
- Test end-to-end storage
- Verify sessions/scans created in XNAT
- Test duplicate detection
- Test various transfer syntaxes
- Test large file uploads

#### Test with Real Clients
- OHIF Viewer upload
- Weasis DICOM send
- dcm4che stowrs tool

```bash
# Test with dcm4che stowrs
stowrs -m http://xnat/xapi/dicomweb/projects/PROJECT/studies \
       --user admin:admin \
       /path/to/dicom/files/*.dcm
```

## Security Considerations

### 1. Authentication
- Require XNAT session authentication
- Use `getSessionUser()` to get current user
- Check user has Edit permission on project

### 2. Authorization
```java
// Check project edit permission
if (!Permissions.canEditProject(user, projectId)) {
    throw new SecurityException("User cannot edit project");
}
```

### 3. Validation
- Validate DICOM syntax
- Check file size limits
- Verify SOP Class UID is supported
- Sanitize file paths
- Prevent path traversal attacks

### 4. Resource Limits
- Max file size per instance (e.g., 2GB)
- Max instances per request (e.g., 1000)
- Max total request size (e.g., 10GB)
- Timeout for long uploads

## Configuration

### Site Configuration Properties
```java
// In DicomWebConfig or site preferences
public class StowRsConfig {
    // Maximum size for single instance (bytes)
    private long maxInstanceSize = 2L * 1024 * 1024 * 1024; // 2GB

    // Maximum instances per STOW request
    private int maxInstancesPerRequest = 1000;

    // Enable duplicate detection
    private boolean detectDuplicates = true;

    // Auto-archive after import (skip prearchive)
    private boolean autoArchive = false;
}
```

## Error Handling

### DICOM Warning Codes (0008,1198 Failure Reason)
- `0xA700` - Out of resources
- `0xA900` - Dataset does not match SOP Class
- `0xC000` - Cannot understand
- `0xC001` - Coercion of Data Elements
- `0xC002` - Data Set does not Match SOP Class

### HTTP Status Codes
- `200 OK` - All instances stored successfully
- `202 Accepted` - Instances queued for processing (async)
- `400 Bad Request` - Invalid DICOM or request format
- `401 Unauthorized` - Not authenticated
- `403 Forbidden` - No edit permission on project
- `409 Conflict` - Duplicate instance UIDs
- `413 Payload Too Large` - Request exceeds size limits
- `500 Internal Server Error` - Unexpected error
- `507 Insufficient Storage` - Out of disk space

## Performance Optimization

### 1. Streaming
- Don't load entire multipart body into memory
- Stream each part directly to temp file
- Use buffered I/O

### 2. Async Processing
- Return 202 Accepted for large uploads
- Process imports in background
- Provide status endpoint to check progress

### 3. Cleanup
- Delete temp files after import completes
- Set file deletion hooks on JVM shutdown
- Monitor temp directory for orphaned files

## Deployment Checklist

- [ ] Implement XnatDicomService.storeInstances()
- [ ] Create StowRsApi REST controller
- [ ] Add multipart parsing logic
- [ ] Integrate with XNAT import pipeline
- [ ] Add permission checks
- [ ] Implement response building
- [ ] Write unit tests
- [ ] Write integration tests
- [ ] Test with OHIF/Weasis
- [ ] Update ARCHITECTURE.md
- [ ] Update docs/DICOMWEB_CONFORMANCE.md
- [ ] Update README.md
- [ ] Update CHANGELOG.md
- [ ] Build and deploy to test XNAT
- [ ] Performance testing
- [ ] Security review

## Future Enhancements

### Phase 2
- Progress tracking for large uploads
- Resume capability for interrupted uploads
- Validation against project-specific SOP Class restrictions
- Integration with XNAT workflow engine

### Phase 3
- Bulk import optimization
- Direct archive writing (bypass prearchive)
- Real-time notifications via WebSocket
- DICOM C-STORE to STOW-RS gateway

## References

- DICOM PS3.18: https://dicom.nema.org/medical/dicom/current/output/html/part18.html
- STOW-RS Specification: Section 10.5
- DICOMweb Standard: https://www.dicomstandard.org/using/dicomweb/store-stow-rs
- XNAT Import API: https://wiki.xnat.org/xnat-api/image-session-import-service-api
- Apache Commons FileUpload: https://commons.apache.org/proper/commons-fileupload/
