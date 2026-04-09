# Changelog

All notable changes to the XNAT DICOMweb Plugin will be documented in this file.

## [1.2.0] - 2026-04-09

### Added
- **STOW-RS (Store Over the Web)** — Full DICOMweb upload support via `POST /xapi/dicomweb/projects/{projectId}/studies`
  - Multipart/related DICOM upload with Mime4J-based parser
  - Two import strategies: **GradualDicomImporter** (default, prearchive pipeline) and **DirectArchive** (immediate archiving, bypasses prearchive)
  - Per-request strategy override via `?strategy=DirectArchive` query parameter
  - Session merging: uploads to the same StudyInstanceUID merge into one session
  - Concurrent upload support with per-study build locks
  - Configurable build delay (`buildDelayMs`) for batching multi-request uploads
  - Overwrite/append mode support for DirectArchive on newer XNAT versions
- **Site-wide DICOMweb endpoints** — Query and retrieve across all projects without project scoping
  - `GET /xapi/dicomweb/studies` — site-wide study search
  - `GET /xapi/dicomweb/studies/{studyUID}/series` — site-wide series search
  - `GET /xapi/dicomweb/studies/{studyUID}/series/{seriesUID}/instances` — site-wide instance search
  - Site-wide WADO-RS retrieval, metadata, rendered, and thumbnail endpoints
  - Site-wide STOW-RS with automatic project routing via DICOM StudyDescription
  - Master toggle (`siteWideEnabled`) to enable/disable all site-wide endpoints
  - **Blacklist/whitelist filtering** — control which projects appear in site-wide queries
  - **Per-project opt-out** — individual projects can exclude themselves from site-wide results
  - All filtering integrated directly into SQL for efficient permission-aware queries
- **Rendered image and thumbnail endpoints** for study, series, instance, and frame levels
  - Rendering parameters: `viewport` (resize), `window` (VOI LUT), `quality` (JPEG compression)
  - Content negotiation via Accept header: JPEG, PNG, GIF
  - Animated GIF output for multi-instance study/series rendering
  - Thumbnail endpoints return smaller default images (128x128)
- **QIDO-RS query parameter filtering** — filter study queries by:
  - StudyInstanceUID, PatientName, PatientID, AccessionNumber (exact match and DICOM wildcard `*`/`?`)
  - StudyDate (exact 8-digit date or wildcard), StudyTime (exact or wildcard)
  - Modality (mapped from XNAT session type)
  - Multiple filters combined with AND logic
- **Pagination** for QIDO-RS — `limit`, `offset` query parameters with `X-Total-Count` response header
- **Bulk data and pixel data endpoints** at study, series, and instance levels
- **Plugin preferences API** — `GET/POST /xapi/dicomweb/prefs` (admin only)
  - `defaultPageSize`, `maxPageSize` — QIDO-RS pagination
  - `bulkDataThreshold` — size threshold for BulkDataURI substitution in metadata
  - `defaultStrategy`, `buildDelayMs` — STOW-RS import configuration
  - `siteWideEnabled`, `filterMode`, `projectList` — site-wide access control
  - `baseUrl` — custom base URL for RetrieveURL generation
- **Project-level configuration** — `GET/PUT /xapi/dicomweb/projects/{projectId}/config/site-wide`
  - Per-project site-wide opt-out flag

### Changed
- **Renamed plugin** from "DICOMweb Proxy" to "DICOMweb Plugin"
  - Plugin ID: `dicomwebproxy` → `dicomwebplugin`
  - Plugin class: `DicomWebProxyPlugin` → `DicomWebPlugin`
  - Bean name: `dicomWebProxyPlugin` → `dicomWebPlugin`
  - All documentation, source files, and properties updated
- **Upgraded dcm4che** from 5.31.0 to 5.33.1
  - Migrated JSON serialization from `javax.json` to `jakarta.json` API
  - Fixed `NoSuchMethodError` on `JSONWriter` constructor with newer XNAT versions
- **Upgraded Gradle** from 7.x to 8.14.3
  - Replaced `io.spring.dependency-management` plugin with Gradle native `platform()` BOM imports
- **Improved QIDO-RS SQL** — permission-aware queries with CTEs for efficient access control
  - Studies without a UID (`xnat_imagesessiondata.uid IS NULL`) are excluded from all results
  - Modality filtering uses reverse-mapped XNAT element names
- **Fat JAR optimization** — removed XNAT-provided libraries from plugin JAR
  - Excluded: SLF4J, Logback, Guava, Gson, commons-io, annotation processors
  - Kept: dcm4che3 classes (for backward compatibility with older XNAT), Mime4J, OpenCV, Weasis, animated-gif-lib, EtherJ
  - Prevents SLF4J/Logback classloading conflicts that caused `NOPLoggerFactory` errors

### Fixed
- **QIDO-RS ILIKE filter SQL** — `ESCAPE '\'` was interpreted as an escaped single quote by PostgreSQL, silently breaking all query parameter filters. Changed to `ESCAPE E'\\\\'` for unambiguous backslash escape.
- **Duplicate plugin bean** — removed manually maintained `dicomweb-plugin.properties` that duplicated the annotation-processor-generated properties file, causing `Duplicate key` startup error
- **`siteWideEnabled` preference type** — API returns boolean string via `String.valueOf()` (by design); test expectations aligned

### Compatibility
- **Backward compatible with older XNAT versions** (1.7.7+)
  - DirectArchiveStrategy loads conditionally via reflection — only instantiated if `DirectArchiveSessionService` is available
  - Falls back to GradualDicomImporter-only mode on older XNAT
  - Temp directory creation deferred to first STOW-RS request (avoids `NullPointerException` when `XDAT.getSiteConfigPreferences()` is unavailable during bean construction)
  - dcm4che3 classes bundled in JAR to fill gaps in older XNAT's dcm4che (e.g., `BulkDataDescriptor`)

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
