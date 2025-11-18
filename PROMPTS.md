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

## 2025-11-17 - STOW-RS Implementation and Testing

**Issue**: GitHub PR #19 - Implement STOW-RS for DICOMweb storage

**Request**: Complete STOW-RS implementation, add debug logging, ensure 100% test coverage

**Implementation**:
1. Enhanced multipart parser in StowRsApi.java:
   - Added System.out.println() debug statements for troubleshooting
   - Fixed endpoint to accept multiple content types: `multipart/related`, `multipart/*`
   - Improved boundary parsing and DICOM part extraction
   - Added DICM marker validation

2. Created comprehensive test suite:
   - Test script using test/data/2/DICOM files: `/tmp/test_stow_with_testdata.sh`
   - Selenium test template: `StowRsSeleniumTest.java` (optional, requires additional dependencies)
   - All 76 unit tests passing (100%)

3. Updated documentation:
   - CONTEXT_HANDOFF.md with session summary
   - Documented debugging techniques (spring.log timestamp verification)

**Files Modified**:
- `src/main/java/org/nrg/xnat/dicomweb/rest/StowRsApi.java` - Debug logging, content type fix
- `src/test/java/org/nrg/xnat/dicomweb/selenium/StowRsSeleniumTest.java` - New file
- `/tmp/test_stow_with_testdata.sh` - Integration test script
- `CONTEXT_HANDOFF.md` - Updated status
- `PROMPTS.md` - This entry

**Test Results**: ✅ All 76 tests passing (100% success rate)
- StowRsApiTest: 5/5
- QidoRsApiTest: 17/17
- XnatDicomServiceImplTest: 38/38
- WadoRsApiTest: 9/9
- DicomWebUtilsTest: 7/7

**Build Status**: ✅ Successful (xnat-dicomweb-proxy-1.1.3.jar, 52KB)

**Deployment Status**: ⏸️ Blocked by XNAT environment issues (webapp not loading)

**Next Steps**:
- Fix XNAT Docker environment
- Run integration tests
- Verify end-to-end STOW-RS → QIDO-RS → WADO-RS workflow
