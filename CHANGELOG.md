# Changelog

All notable changes to the XNAT DICOMweb Plugin will be documented in this file.

## [1.1.3] - 2025-11-12

### Added
- **WADO-RS frame-level retrieval** - `GET /xapi/dicomweb/.../frames/{frameList}` (Issue #17)
  - Enables OHIF viewer to request individual frames from multi-frame DICOM instances
  - Supports comma-separated frame numbers (e.g., `frames/1` or `frames/1,2,3`)
  - Returns single frames as `application/octet-stream`
  - Returns multiple frames as `multipart/related`
  - Handles both uncompressed and compressed pixel data correctly

### Fixed
- **Frame extraction for compressed DICOM data**
  - Uncompressed data: Direct byte[] extraction (fast)
  - Compressed data: ImageIO decompression (correct, avoids fragment mapping issues)
  - Prevents corruption from incorrectly accessing Basic Offset Table as frame data
  - Properly handles multi-fragment frames in compressed transfer syntaxes

### Technical Details
- New `retrieveFrames()` service method with frame parsing and extraction logic
- `extractFramePixelData()` correctly distinguishes uncompressed vs compressed data
- Never transcodes to PNG/JPEG - always returns raw pixel data
- 33 unit tests (added 15 tests for frame retrieval functionality)
- Comprehensive test coverage: parsing, REST endpoints, PNG/JPEG prevention, compressed handling
- Test data included: anonymized 34KB PET scan sample (no external dependencies)

### Compliance
- ✅ DICOM PS3.18 Section 10.4 - WADO-RS Retrieve Transaction
- ✅ 1-based frame indexing per specification
- ✅ Proper multipart/related response format

## [1.1.2] - 2025-11-11

### Fixed
- **Critical**: Fixed NullPointerException in study metadata endpoint that made it completely unusable
- Study metadata endpoint now correctly returns metadata for all instances in a study (DICOMweb compliant per DICOM PS3.18)
- Endpoint routing priority adjusted to prevent conflicts between study retrieval and metadata endpoints

### Added
- New `retrieveAllStudyInstanceMetadata()` service method that properly iterates all series in a study
- Comprehensive unit tests for study metadata endpoint (WadoRsApiTest - 4 tests)
- End-to-end tests for DICOMweb plugin (DicomWebPluginE2ETest - 6 tests)
- Detailed logging for debugging study metadata requests
- Documentation: `docs/STUDY_METADATA_FIX.md` with technical details and deployment instructions

### Changed
- Study metadata endpoint now returns array of all instance metadata instead of just study-level attributes
- Updated test page with study metadata endpoint verification
- Improved error handling and logging in WadoRsApi

### Technical Details
- The fix creates a dedicated method that iterates through all series in a study and collects instance metadata
- No longer passes null parameters that caused NullPointerException
- Complies with DICOM PS3.18 specification for Retrieve Study Metadata transaction
- Test coverage includes regression tests to prevent similar issues
- Response format: JSON array of DICOM instances with SOP Class UID, SOP Instance UID, and all DICOM attributes

## [1.1.1] - 2025-01-07

### Added
- **Study-level metadata endpoint** - `GET /xapi/dicomweb/projects/{id}/studies/{studyUID}/metadata`
  - Returns comprehensive study-level DICOM metadata
  - Includes NumberOfStudyRelatedSeries and NumberOfStudyRelatedInstances
  - Includes StudyTime, InstitutionName, and ModalitiesInStudy
  - Enhanced patient identification attributes

### Enhanced
- Study attributes now include series and instance counts
- Improved metadata completeness for DICOMweb compliance

## [1.1.0] - 2024-11-07

### Added
- Development roadmap for future improvements
- Maven repository path for improved dependency resolution

## [1.0.0] - 2024-10-31

### Added

#### Core Functionality
- Full DICOMweb API implementation (QIDO-RS and WADO-RS)
- XNAT 1.9.x data model integration
- Database-driven queries using XFTTable
- File system access to XNAT archive
- DICOM to JSON conversion using DCM4CHE
- Multipart response generation for bulk retrieval
- XNAT authentication and authorization support

#### API Endpoints

**QIDO-RS (Query):**
- `GET /xapi/dicomweb/projects/{id}/studies` - Search studies
- `GET /xapi/dicomweb/projects/{id}/studies/{studyUID}/series` - Search series
- `GET /xapi/dicomweb/projects/{id}/studies/{studyUID}/series/{seriesUID}/instances` - Search instances

**WADO-RS (Retrieve):**
- `GET /xapi/dicomweb/projects/{id}/studies/{studyUID}` - Retrieve study
- `GET /xapi/dicomweb/projects/{id}/studies/{studyUID}/series/{seriesUID}` - Retrieve series
- `GET /xapi/dicomweb/projects/{id}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}` - Retrieve instance
- `GET /xapi/dicomweb/projects/{id}/.../instances/{instanceUID}/metadata` - Get instance metadata
- `GET /xapi/dicomweb/projects/{id}/.../series/{seriesUID}/metadata` - Get series metadata

#### Test Infrastructure
- **Built-in Test Page** - Interactive web UI for testing all endpoints ⭐
  - Access at `/xapi/dicomweb/test`
  - Modern, responsive design
  - Real-time response display
  - Copy to clipboard functionality
  - Response time tracking
  - No external tools required
- Unit tests for all major components
- Integration test examples
- Test data factory for mocking

#### Documentation
- README.md - User guide and quick start
- ARCHITECTURE.md - Detailed system architecture
- IMPLEMENTATION.md - Developer implementation guide
- TESTING.md - Comprehensive testing guide
- INSTALLATION.md - Installation and deployment guide
- XNAT_1.9_IMPLEMENTATION.md - XNAT 1.9.x specific details
- DEPLOYMENT_CHECKLIST.md - Step-by-step deployment guide
- TEST_PAGE_GUIDE.md - Test page usage instructions ⭐

#### Configuration
- Configurable archive path via system property
- Standard XNAT archive structure support
- CORS configuration ready for web viewers

### Technical Details

#### Dependencies
- XNAT 1.9.0 API
- DCM4CHE 5.29.2 for DICOM handling
- Spring Framework 5.x
- Java 8 compatibility
- Gradle 5.6+ build system

#### Data Model Mapping
- XnatProjectdata → DICOMweb project context
- XnatImagesessiondata → DICOM Study
- XnatImagescandata → DICOM Series
- DICOM files → DICOM Instances

#### Performance Features
- Direct file streaming (no buffering)
- Database query optimization
- Efficient DICOM parsing
- Minimal memory footprint (~20KB JAR)

### Viewer Compatibility
- ✅ OHIF Viewer v3.x
- ✅ VolView 5.x
- ✅ Weasis 4.x
- ✅ Any DICOMweb-compliant viewer

### Known Limitations
- STOW-RS (Store) not implemented
- Query parameters not yet supported
- Pagination not implemented
- Frame-level retrieval not supported
- Rendered image formats (JPEG/PNG) not supported

### Security
- Respects XNAT authentication
- Enforces project-level permissions
- No authentication bypass
- Test page requires login
- Session-based security

### Build Information
- Build time: ~6 seconds
- Plugin size: ~20KB
- Java source files: 11
- Test files: 6
- Documentation files: 8
- Lines of code: ~2,500

## Future Enhancements

### Planned for v1.1.0
- Query parameter support (PatientName, StudyDate filters)
- Pagination (limit/offset parameters)
- Response caching for better performance
- Metrics and monitoring

### Planned for v1.2.0
- Frame-level retrieval
- Rendered image formats (JPEG, PNG)
- Thumbnail generation
- Bulk data handling improvements

### Planned for v2.0.0
- STOW-RS (Store) support
- UPS (Unified Procedure Step) support
- Worklist support
- WebSocket notifications

## Migration Notes

### From Stub Implementation
- Replace stub with working implementation
- Test with real XNAT data
- Verify archive path configuration
- Check database indexes

### From XNAT 1.8.x
- API compatible, minor testing recommended
- Data model differences minimal
- Archive structure unchanged

### From XNAT 1.7.x
- Significant data model changes
- Extensive testing required
- May need code modifications

## Compatibility Matrix

| Component | Version | Status |
|-----------|---------|--------|
| XNAT | 1.9.0+ | ✅ Tested |
| XNAT | 1.8.x | ⚠️ Should work |
| XNAT | 1.7.x | ❌ Not tested |
| Java | 8 | ✅ Tested |
| Java | 11+ | ✅ Compatible |
| OHIF | v3.x | ✅ Tested |
| VolView | 5.x | ✅ Tested |
| Weasis | 4.x | ✅ Compatible |

## Contributors

- Implementation: XNAT DICOMweb Plugin Team
- Architecture: Based on DICOM PS3.18 standard
- Testing: Comprehensive test suite included

## License

Same as XNAT

## Links

- Documentation: See documentation files in repository
- Issues: Report via project issue tracker
- DICOM Standard: http://dicom.nema.org/medical/dicom/current/output/html/part18.html
- XNAT: https://www.xnat.org/

---

**Legend:**
- ⭐ New feature
- ✅ Tested
- ⚠️ Compatibility warning
- ❌ Not supported
