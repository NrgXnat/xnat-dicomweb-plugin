# XNAT DICOMweb Plugin - Development Prompts

## 2025-11-12 - WADO-RS Frame-Level Retrieval

**Issue**: GitHub Issue #17 - OHIF viewer requests frame-level retrieval but gets 404 errors

**Request**: Implement WADO-RS frame retrieval endpoint per DICOM PS3.18 spec

**Endpoint**: `GET /studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/frames/{frameList}`

**Implementation**:
1. Added `retrieveFrames()` method to XnatDicomService interface
2. Implemented frame extraction logic in XnatDicomServiceImpl:
   - Parse comma-separated frame numbers (1-based)
   - Extract pixel data for each frame from DICOM file
   - Handle both uncompressed and compressed pixel data
   - Support multi-frame DICOM instances
3. Added REST endpoint in WadoRsApi:
   - Single frame returns `application/octet-stream`
   - Multiple frames return `multipart/related` response
4. Created `createMultipartFrameResponse()` helper method

**DICOM Standard Reference**:
- DICOM PS3.18 Section 10.4 - WADO-RS Retrieve Transaction
- Table 10.4.1.6-1 - Retrieve Frame Resource Attributes

**Files Modified**:
- `src/main/java/org/nrg/xnat/dicomweb/service/XnatDicomService.java`
- `src/main/java/org/nrg/xnat/dicomweb/service/XnatDicomServiceImpl.java`
- `src/main/java/org/nrg/xnat/dicomweb/rest/WadoRsApi.java`

**Build Status**: ✅ Successful (Gradle 5.6.4, Java 8)

**Codex Review Feedback** (2025-11-12):
- **Issue**: PNG transcoding of compressed frames while advertising as `application/octet-stream`
- **Fix**: Refactored `extractFramePixelData()` to:
  - Extract compressed frames directly from DICOM Fragments
  - Return raw uncompressed pixel data for uncompressed frames
  - Use ImageIO fallback only when necessary, extracting raw pixels from BufferedImage
  - Never transcode to PNG format
- **Commit**: 72e11b7

**Testing**: ✅ All 27 tests pass

**Next Steps**: Deploy to XNAT and test with OHIF viewer
