# XNAT DICOMweb Plugin - Development Roadmap

This document outlines the planned improvements for the XNAT DICOMweb Plugin plugin, organized by priority and implementation phases.

## Overview

The roadmap is divided into three phases:
- **Phase 1**: Critical improvements for production readiness
- **Phase 2**: Performance and usability enhancements
- **Phase 3**: Advanced features and DICOMweb compliance

## Issue Priority Matrix

| Priority | Issue | Effort | Impact |
|----------|-------|--------|--------|
| P0 | #5 Enhanced error handling | Medium | High |
| P0 | #6 Externalized configuration | Low | High |
| P1 | #2 Caching implementation | Medium | High |
| P1 | #4 File reading optimization | Medium | High |
| P2 | #1 Query parameter support | High | Medium |
| P2 | #3 Pagination support | Medium | Medium |
| P3 | #8 Multi-frame DICOM support | Low | Medium |
| P3 | #7 STOW-RS implementation | High | Medium |

**Effort Levels:**
- Low: 1-3 days
- Medium: 4-10 days
- High: 2-4 weeks

---

## Phase 1: Production Readiness (Weeks 1-3)

### Goal
Improve stability, debuggability, and deployment flexibility for production environments.

### Issues

#### #5 Enhanced Error Handling
**Priority:** P0
**Effort:** Medium (5-7 days)
**Dependencies:** None

**Implementation Plan:**
1. Create custom exception hierarchy
   - `DicomWebException` (base)
   - `ResourceNotFoundException` (404)
   - `UnauthorizedException` (403)
   - `InvalidRequestException` (400)

2. Add exception handling in service layer
   - `XnatDicomServiceImpl` should throw typed exceptions
   - Add validation for UIDs, project access

3. Create global exception handler
   - `@ControllerAdvice` class for REST controllers
   - Map exceptions to appropriate HTTP status codes
   - Return structured JSON error responses

4. Add correlation IDs for request tracking
   - Use MDC (Mapped Diagnostic Context) with SLF4J
   - Include in error responses and logs

**Files to Modify:**
- New: `org.nrg.xnat.dicomweb.exception.*`
- New: `org.nrg.xnat.dicomweb.rest.GlobalExceptionHandler`
- Modify: `XnatDicomServiceImpl.java`
- Modify: All REST controllers

**Testing:**
- Unit tests for exception mapping
- Integration tests for error scenarios
- Manual testing with invalid requests

---

#### #6 Externalized Configuration
**Priority:** P0
**Effort:** Low (2-3 days)
**Dependencies:** None

**Implementation Plan:**
1. Create configuration properties class
   ```java
   @ConfigurationProperties(prefix = "xnat.dicomweb")
   public class DicomWebProperties {
       private String archivePath = "/data/xnat/archive";
       private Cache cache = new Cache();
       private Pagination pagination = new Pagination();
       private Rendering rendering = new Rendering();
       // ... nested classes
   }
   ```

2. Update Spring configuration
   - Enable `@EnableConfigurationProperties`
   - Inject properties into services

3. Replace hardcoded values
   - Archive path system property → configuration
   - CORS settings → configuration
   - Default page sizes → configuration

4. Document all properties
   - Add to README.md
   - Provide example `application.yml`

**Files to Modify:**
- New: `org.nrg.xnat.dicomweb.config.DicomWebProperties`
- Modify: `DicomWebConfig.java`
- Modify: `XnatDicomServiceImpl.java`
- Update: `README.md`

**Configuration Example:**
```yaml
xnat:
  dicomweb:
    archive-path: /data/xnat/archive
    cache:
      enabled: true
      max-size: 1000
      ttl-minutes: 60
    pagination:
      default-limit: 100
      max-limit: 1000
    rendering:
      quality: 0.85
      max-dimension: 2048
```

---

## Phase 2: Performance & Usability (Weeks 4-8)

### Goal
Optimize performance for large datasets and improve API usability.

### Issues

#### #2 Caching Implementation
**Priority:** P1
**Effort:** Medium (6-8 days)
**Dependencies:** #6 (configuration)

**Implementation Plan:**
1. Add Spring Cache dependencies
   ```gradle
   implementation 'org.springframework.boot:spring-boot-starter-cache'
   implementation 'com.github.ben-manes.caffeine:caffeine'
   ```

2. Configure cache manager
   - Use Caffeine for in-memory caching
   - Configure multiple caches with different TTLs
   - Add cache statistics/monitoring

3. Add caching annotations
   - `@Cacheable` for read operations
   - `@CacheEvict` for invalidation scenarios
   - Cache keys based on project/study/series/instance UIDs

4. Cache strategy
   - **Metadata cache**: DICOM Attributes by UID (TTL: 1 hour)
   - **File path cache**: File locations by UID (TTL: 1 hour)
   - **Session cache**: Session → Study UID mapping (TTL: 30 min)

**Cache Regions:**
```yaml
dicomweb.metadata      # DICOM Attributes
dicomweb.filepaths     # File path lookups
dicomweb.sessions      # Session mappings
```

**Files to Modify:**
- Modify: `DicomWebConfig.java` (add cache configuration)
- Modify: `XnatDicomServiceImpl.java` (add cache annotations)
- Modify: `DicomWebProperties.java` (add cache settings)
- New: Cache monitoring endpoint (optional)

**Considerations:**
- Memory usage limits
- Cache warming strategies
- Invalidation on data updates
- Distributed cache for multi-instance deployments (future)

---

#### #4 File Reading Optimization
**Priority:** P1
**Effort:** Medium (5-7 days)
**Dependencies:** #2 (caching helps)

**Implementation Plan:**
1. Analyze current file reading patterns
   - Profile `readDicomFilesFromScan`
   - Profile `findDicomFileInScan`
   - Identify redundant reads

2. Implement file path caching
   - Build index of UID → File mappings
   - Cache after first scan
   - Reuse for subsequent queries

3. Optimize catalog resolution
   - Cache `CatalogData` objects
   - Reduce filesystem traversal
   - Use catalog XML more efficiently

4. Lazy metadata loading
   - Read only required DICOM tags for queries
   - Use `DicomInputStream.readFileMetaInformation()`
   - Full metadata only when needed

5. Connection pooling for file access
   - Reuse file handles where safe
   - Consider NIO for large files

**Performance Targets:**
- Reduce file reads by 50%
- Improve query response time for series with 100+ instances
- Reduce disk I/O for repeated queries

**Files to Modify:**
- Modify: `XnatDicomServiceImpl.java`
  - `readDicomFilesFromScan`
  - `findDicomFileInScan`
  - `resolveDicomFiles`
- Add metrics/timing logs

**Testing:**
- Performance benchmarks
- Load testing with large series
- Verify no file descriptor leaks

---

#### #1 Query Parameter Support
**Priority:** P2
**Effort:** High (10-14 days)
**Dependencies:** #5 (for validation errors)

**Implementation Plan:**
1. Define supported query parameters
   - Study level: PatientName, PatientID, StudyDate, StudyTime, AccessionNumber
   - Series level: Modality, SeriesDescription, SeriesNumber
   - Instance level: SOPInstanceUID, InstanceNumber

2. Create query parameter parser
   - Parse from HTTP request
   - Convert to DICOM Attributes
   - Handle wildcards and ranges (e.g., StudyDate range)

3. Implement matching logic
   - DICOM matching rules (exact, wildcard, range)
   - Filter results in service layer
   - Support multiple criteria (AND logic)

4. Update REST endpoints
   - Accept `@RequestParam Map<String, String>`
   - Pass to service methods
   - Document in Swagger annotations

5. DICOM compliance
   - Follow PS3.18 QIDO-RS specification
   - Support includefield parameter
   - Handle fuzzy matching for names

**Example Usage:**
```
GET /dicomweb/projects/PROJECT1/studies?PatientName=Smith&StudyDate=20240101-20241231
GET /dicomweb/projects/PROJECT1/studies/{studyUID}/series?Modality=MR
```

**Files to Modify:**
- New: `org.nrg.xnat.dicomweb.query.QueryParser`
- New: `org.nrg.xnat.dicomweb.query.DicomMatcher`
- Modify: `QidoRsApi.java` (all endpoints)
- Modify: `XnatDicomService.java` (add query parameter)
- Modify: `XnatDicomServiceImpl.java` (implement filtering)

**Testing:**
- Unit tests for query parsing
- Unit tests for matching logic
- Integration tests with various query combinations
- Test with OHIF/VolView

---

#### #3 Pagination Support
**Priority:** P2
**Effort:** Medium (4-6 days)
**Dependencies:** #6 (configuration), #1 (query params)

**Implementation Plan:**
1. Add pagination parameters
   - `limit`: Number of results per page
   - `offset`: Starting index
   - Default and maximum limits from configuration

2. Implement in service layer
   - Return paginated subsets
   - Calculate total count
   - Handle edge cases (offset beyond results)

3. Add response headers
   - `X-Total-Count`: Total results available
   - `Link`: Navigation links (next/prev)
   - Follow DICOMweb best practices

4. Update REST controllers
   - Accept pagination parameters
   - Set response headers
   - Document in API

**Example:**
```
GET /dicomweb/projects/PROJECT1/studies?limit=50&offset=0
Response headers:
  X-Total-Count: 237
  Link: </dicomweb/projects/PROJECT1/studies?limit=50&offset=50>; rel="next"
```

**Files to Modify:**
- Modify: `QidoRsApi.java` (all search endpoints)
- Modify: `XnatDicomService.java` (add pagination params)
- Modify: `XnatDicomServiceImpl.java` (implement pagination)
- New: `org.nrg.xnat.dicomweb.model.PageRequest`
- New: `org.nrg.xnat.dicomweb.model.Page`

**Testing:**
- Test with small and large result sets
- Test boundary conditions
- Verify headers are correct
- Performance testing with pagination vs full results

---

## Phase 3: Advanced Features (Weeks 9+)

### Goal
Complete DICOMweb implementation and advanced use cases.

### Issues

#### #8 Multi-frame DICOM Support
**Priority:** P3
**Effort:** Low (2-4 days)
**Dependencies:** None

**Implementation Plan:**
1. Add frame parameter support
   - Accept `?frame=N` query parameter
   - Default to middle frame instead of first
   - Validate frame number

2. Update rendering method
   - Detect number of frames in DICOM
   - Extract requested frame
   - Handle single-frame gracefully

3. Optional: Animated GIF support
   - Generate GIF from all frames
   - Accept `?animated=true` parameter
   - Consider frame rate from DICOM metadata

**Files to Modify:**
- Modify: `WadoRsApi.java:retrieveInstanceRendered`
- Modify: `XnatDicomServiceImpl.java:renderDicomToJpeg`
- Modify: `XnatDicomService.java` (add frame parameter)

**Example:**
```
GET /dicomweb/.../instances/{uid}/rendered?frame=10
GET /dicomweb/.../instances/{uid}/rendered?animated=true
```

---

#### #7 STOW-RS Implementation
**Priority:** P3
**Effort:** High (15-20 days)
**Dependencies:** #5, #6

**Implementation Plan:**
1. Create STOW-RS REST endpoint
   ```java
   POST /dicomweb/projects/{projectId}/studies
   Content-Type: multipart/related; type="application/dicom"
   ```

2. Implement multipart parser
   - Parse multipart/related request
   - Extract DICOM instances
   - Validate DICOM format

3. XNAT integration
   - Create or find existing session
   - Map DICOM to XNAT scan
   - Store files in archive
   - Update catalog XML

4. Response format
   - Return STOW-RS XML/JSON response
   - Include success/failure per instance
   - Follow PS3.18 specification

5. Validation and security
   - Verify user has write permissions
   - Validate DICOM data
   - Handle duplicates
   - Prevent data overwrite

**Files to Create:**
- `org.nrg.xnat.dicomweb.rest.StowRsApi`
- `org.nrg.xnat.dicomweb.service.DicomStorageService`
- `org.nrg.xnat.dicomweb.multipart.MultipartParser`

**Files to Modify:**
- `XnatDicomService.java` (add storage methods)

**Challenges:**
- XNAT data model mapping
- Transaction management
- Error handling for partial failures
- Integration with prearchive vs archive
- Handling of non-DICOM attributes

**Testing:**
- Unit tests for multipart parsing
- Integration tests with XNAT data creation
- Test with DICOM sending tools (dcm4che, Horos)
- Verify XNAT catalog consistency

---

## Implementation Guidelines

### General Principles
1. **Backward Compatibility**: Don't break existing functionality
2. **Testing**: Each issue should include unit and integration tests
3. **Documentation**: Update README and inline documentation
4. **Logging**: Add appropriate logging at DEBUG and INFO levels
5. **Performance**: Profile changes that affect query performance

### Code Quality Standards
- Follow existing code style
- Add Javadoc for public methods
- Use SLF4J for logging
- Handle exceptions appropriately
- Write meaningful commit messages

### Testing Strategy
- Unit tests for business logic
- Integration tests with XNAT test framework
- Manual testing with OHIF and VolView viewers
- Performance benchmarks for optimization issues

### Documentation Requirements
Each issue should update:
- README.md (if user-facing)
- Inline code documentation
- API documentation (Swagger annotations)
- Configuration examples

---

## Risk Assessment

### Technical Risks

**Caching (#2)**
- Risk: Memory exhaustion with large datasets
- Mitigation: Configurable limits, monitoring, eviction policies

**File Optimization (#4)**
- Risk: File descriptor leaks
- Mitigation: Proper resource management, testing, monitoring

**STOW-RS (#7)**
- Risk: Data corruption or inconsistency in XNAT
- Mitigation: Thorough testing, transaction management, validation

**Query Parameters (#1)**
- Risk: Complex matching logic with edge cases
- Mitigation: Follow DICOM standard strictly, extensive test cases

### Operational Risks

**Configuration Changes (#6)**
- Risk: Breaking existing deployments
- Mitigation: Sensible defaults, migration guide, backward compatibility

**Performance Changes (#2, #4)**
- Risk: Unexpected performance degradation
- Mitigation: Benchmark before/after, gradual rollout, feature flags

---

## Success Metrics

### Phase 1
- [ ] All exceptions return appropriate HTTP status codes
- [ ] Configuration fully externalized
- [ ] Zero hardcoded paths in code
- [ ] Error logs include correlation IDs

### Phase 2
- [ ] Query response time reduced by 40% with caching
- [ ] Support for 1000+ study result sets with pagination
- [ ] QIDO-RS query parameters work with OHIF
- [ ] Cache hit rate > 60% for metadata queries

### Phase 3
- [ ] Multi-frame rendering supported
- [ ] STOW-RS successfully stores DICOM from external tools
- [ ] Full DICOMweb compliance for QIDO-RS/WADO-RS/STOW-RS

---

## Future Considerations

### Beyond Current Roadmap
- **Authentication alternatives**: OAuth2, SAML integration
- **Audit logging**: Track all DICOMweb access
- **Compression**: Support transfer syntax negotiation
- **Thumbnails**: Pre-generate and cache thumbnails
- **Bulk retrieval**: Optimize for large dataset downloads
- **DICOM SR/PDF support**: Handle structured reports
- **UPS (Unified Procedure Step)**: Workflow support
- **Multi-tenancy**: Performance isolation per project
- **Cloud storage**: Support for S3/object storage backends
- **Real-time updates**: WebSocket notifications for new data

### DICOMweb Specification Gaps
- WADO-URI (legacy, lower priority)
- Additional QIDO-RS includefield options
- Fine-grained accept headers for content negotiation
- Full conformance statement generation

---

## Release Strategy

### Version Planning
- **v1.1.0**: Phase 1 (error handling, configuration)
- **v1.2.0**: Phase 2 performance (caching, optimization)
- **v1.3.0**: Phase 2 usability (query params, pagination)
- **v2.0.0**: Phase 3 (STOW-RS, advanced features)

### Release Criteria
Each release must:
1. Pass all existing and new tests
2. Include migration guide if needed
3. Update documentation
4. Be tested with OHIF and VolView
5. Include performance benchmarks

---

## Getting Started

To contribute to these improvements:

1. Review the issue on GitHub
2. Read this roadmap for context
3. Check dependencies are completed
4. Create a feature branch
5. Implement with tests
6. Submit PR with documentation updates

For questions or discussions, use GitHub issues or discussions.
