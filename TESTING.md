# Testing Guide for XNAT DICOMweb Plugin

## Overview

This document describes the test suite for the XNAT DICOMweb Plugin and how to run the tests.

## Test Structure

The test suite is organized into several layers:

### 1. Unit Tests

Located in `src/test/java/org/nrg/xnat/dicomweb/`

#### DicomWebUtilsTest
Tests utility functions for DICOM/JSON conversion and content type handling.

**Coverage:**
- DICOM to JSON conversion
- Empty attributes handling
- Content type generation
- Special character handling

**Run with:**
```bash
./gradlew test --tests DicomWebUtilsTest
```

#### XnatDicomServiceImplTest
Tests the service layer that interfaces with XNAT data models.

**Coverage:**
- Search studies (single and multiple)
- Search series (single and multiple)
- Search instances
- Retrieve instance operations
- Retrieve metadata
- Retrieve study and series
- Rendered image retrieval
- Error handling for non-existent data
- File path resolution

**Run with:**
```bash
./gradlew test --tests XnatDicomServiceImplTest
```

#### QidoRsApiTest
Tests the QIDO-RS (Query) REST API endpoints.

**Coverage:**
- Search studies endpoint
- Search series endpoint
- Search instances endpoint
- Empty result handling
- Exception handling
- Content type validation

**Run with:**
```bash
./gradlew test --tests QidoRsApiTest
```

#### WadoRsApiTest
Tests the WADO-RS (Retrieve) REST API endpoints.

**Coverage:**
- Retrieve instance
- Retrieve instance metadata
- Retrieve rendered instance
- Retrieve series
- Retrieve study
- Retrieve series metadata
- Not found scenarios
- Multipart response handling
- Content type validation

**Run with:**
```bash
./gradlew test --tests WadoRsApiTest
```

### 2. Integration Tests

Located in `src/test/java/org/nrg/xnat/dicomweb/rest/DicomWebIntegrationTest.java`

**Note:** Integration tests are marked with `@Ignore` by default as they require a running XNAT instance with test data.

**To run integration tests:**

1. Set up a test XNAT instance
2. Load test DICOM data into a project
3. Configure test constants in `DicomWebIntegrationTest.java`:
   ```java
   private static final String TEST_PROJECT_ID = "YOUR_PROJECT";
   private static final String TEST_STUDY_UID = "YOUR_STUDY_UID";
   private static final String TEST_SERIES_UID = "YOUR_SERIES_UID";
   private static final String TEST_INSTANCE_UID = "YOUR_INSTANCE_UID";
   ```
4. Remove the `@Ignore` annotation
5. Run: `./gradlew test --tests DicomWebIntegrationTest`

## Test Utilities

### TestDataFactory

Located in `src/test/java/org/nrg/xnat/dicomweb/utils/TestDataFactory.java`

Provides factory methods for creating mock objects and test data:

- `createMockUser()` - Mock XNAT user
- `createMockProject()` - Mock XNAT project
- `createMockSession()` - Mock imaging session (study)
- `createMockScan()` - Mock scan (series)
- `createStudyAttributes()` - DICOM study attributes
- `createSeriesAttributes()` - DICOM series attributes
- `createInstanceAttributes()` - DICOM instance attributes

## Running All Tests

```bash
# Run all unit tests
./gradlew test

# Run specific test class
./gradlew test --tests QidoRsApiTest

# Run specific test method
./gradlew test --tests QidoRsApiTest.testSearchStudiesSuccess

# Run with verbose output
./gradlew test --info

# Generate test report
./gradlew test
# Report available at: build/reports/tests/test/index.html
```

## Test Coverage

Generate code coverage report:

```bash
./gradlew test jacocoTestReport
```

Coverage report available at: `build/reports/jacoco/test/html/index.html`

## Manual Testing with cURL

### QIDO-RS Endpoints

```bash
# Search studies
curl -X GET "http://localhost:8080/xapi/dicomweb/projects/PROJECT_ID/studies" \
  -H "Accept: application/dicom+json" \
  -u username:password

# Search series
curl -X GET "http://localhost:8080/xapi/dicomweb/projects/PROJECT_ID/studies/STUDY_UID/series" \
  -H "Accept: application/dicom+json" \
  -u username:password

# Search instances
curl -X GET "http://localhost:8080/xapi/dicomweb/projects/PROJECT_ID/studies/STUDY_UID/series/SERIES_UID/instances" \
  -H "Accept: application/dicom+json" \
  -u username:password
```

### WADO-RS Endpoints

```bash
# Retrieve instance
curl -X GET "http://localhost:8080/xapi/dicomweb/projects/PROJECT_ID/studies/STUDY_UID/series/SERIES_UID/instances/INSTANCE_UID" \
  -H "Accept: application/dicom" \
  -u username:password \
  -o instance.dcm

# Retrieve instance metadata
curl -X GET "http://localhost:8080/xapi/dicomweb/projects/PROJECT_ID/studies/STUDY_UID/series/SERIES_UID/instances/INSTANCE_UID/metadata" \
  -H "Accept: application/dicom+json" \
  -u username:password

# Retrieve rendered instance
curl -X GET "http://localhost:8080/xapi/dicomweb/projects/PROJECT_ID/studies/STUDY_UID/series/SERIES_UID/instances/INSTANCE_UID/rendered" \
  -H "Accept: image/jpeg" \
  -u username:password \
  -o rendered.jpg

# Retrieve series
curl -X GET "http://localhost:8080/xapi/dicomweb/projects/PROJECT_ID/studies/STUDY_UID/series/SERIES_UID" \
  -H "Accept: multipart/related" \
  -u username:password \
  -o series.multipart

# Retrieve series metadata
curl -X GET "http://localhost:8080/xapi/dicomweb/projects/PROJECT_ID/studies/STUDY_UID/series/SERIES_UID/metadata" \
  -H "Accept: application/dicom+json" \
  -u username:password
```

## Testing with OHIF Viewer

1. Configure OHIF with your XNAT endpoint:
```javascript
window.config = {
  servers: {
    dicomWeb: [
      {
        name: 'XNAT',
        qidoRoot: 'http://localhost:8080/xapi/dicomweb/projects/PROJECT_ID',
        wadoRoot: 'http://localhost:8080/xapi/dicomweb/projects/PROJECT_ID',
      },
    ],
  },
};
```

2. Launch OHIF and verify:
   - Study list loads correctly
   - Series thumbnails display
   - Images can be viewed
   - Navigation works properly

## Testing with VolView

1. Open VolView
2. Go to File → Remote
3. Enter DICOMweb URL: `http://localhost:8080/xapi/dicomweb/projects/PROJECT_ID`
4. Authenticate with XNAT credentials
5. Verify study list and image loading

## Continuous Integration

For CI/CD pipelines, add to your workflow:

```yaml
- name: Run Tests
  run: ./gradlew test

- name: Generate Coverage Report
  run: ./gradlew jacocoTestReport

- name: Upload Coverage
  uses: codecov/codecov-action@v3
  with:
    files: ./build/reports/jacoco/test/jacocoTestReport.xml
```

## Troubleshooting

### Tests fail with "Cannot mock XnatProjectdata"

Ensure you're using MockedStatic for static methods:
```java
try (MockedStatic<XnatProjectdata> mockedStatic = mockStatic(XnatProjectdata.class)) {
    mockedStatic.when(() -> XnatProjectdata.getXnatProjectdatasById(...))
                .thenReturn(mockProject);
    // Your test code
}
```

### JSON parsing errors in tests

Verify DCM4CHE and JSON dependencies are properly configured in `build.gradle`.

### Integration tests hang

Check that:
1. XNAT is running and accessible
2. Test credentials are correct
3. Test data exists in XNAT
4. Network connectivity is available

## Best Practices

1. **Mock External Dependencies**: Always mock XNAT data models and file system access
2. **Test Edge Cases**: Include tests for empty results, not found, and error conditions
3. **Verify Content Types**: Ensure responses have correct DICOMweb content types
4. **Clean Test Data**: Use consistent test UIDs from TestDataFactory
5. **Isolate Tests**: Each test should be independent and not rely on test execution order

## Contributing

When adding new features:

1. Write unit tests for new classes/methods
2. Update integration tests if adding new endpoints
3. Add test data factories for new XNAT data types
4. Update this documentation
5. Ensure all tests pass before submitting PR
